package org.com.sharekhan.service;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.broker.BrokerServiceFactory;
import org.com.sharekhan.service.broker.TriggerPriceEntryBrokerService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeExecutionServiceBrokerSideEntryTest {

    @Test
    void acceptedBrokerSideEntryTriggerCreatesPendingTradeAndStartsPolling() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(true)
                .orderId("182038823")
                .status("Pending")
                .attemptedPrice(123.45)
                .build());

        TriggerTradeRequestEntity saved = ctx.service.executeTrade(optionRequest());

        assertThat(saved.getStatus()).isEqualTo(TriggeredTradeStatus.TRIGGERED);
        TriggeredTradeSetupEntity liveTrade = ctx.savedTrade.get();
        assertThat(liveTrade).isNotNull();
        assertThat(liveTrade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(liveTrade.getOrderId()).isEqualTo("182038823");
        assertThat(liveTrade.getEntryPrice()).isEqualTo(123.45);
        assertThat(liveTrade.getTriggerRequestId()).isEqualTo(77L);

        verify(ctx.triggerRepo).claimIfStatusEquals(
                77L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.TRIGGERED.name());
        verify(ctx.broker).placeTriggerPriceEntryOrder(any(), any(BrokerContext.class), anyDouble());
        verify(ctx.eventPublisher).publishEvent(any(OrderPlacedEvent.class));
    }

    @Test
    void rejectedBrokerSideEntryTriggerLeavesRequestPendingForOriginalFlow() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(false)
                .status("Rejected")
                .rejectionReason("RMS rejected trigger order")
                .attemptedPrice(123.45)
                .build());

        TriggerTradeRequestEntity saved = ctx.service.executeTrade(optionRequest());

        assertThat(saved.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(ctx.savedTrade.get()).isNull();
        verify(ctx.triggerRepo).claimIfStatusEquals(
                77L,
                TriggeredTradeStatus.TRIGGERED.name(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name());
        verify(ctx.triggeredRepo, never()).save(any());
        verify(ctx.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createExecutedTradePlacesTargetOrderForNonSpotTarget() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(true)
                .orderId("182038823")
                .status("Pending")
                .build());
        when(ctx.broker.placeExitOrder(any(), any(BrokerContext.class), eq(150.0)))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("TARGET-ORDER-1")
                        .status("Pending")
                        .attemptedPrice(150.0)
                        .build());

        TriggeredTradeSetupEntity created = ctx.service.createExecutedTrade(optionRequest());

        assertThat(created.getStatus()).isEqualTo(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        assertThat(created.getExitOrderId()).isEqualTo("TARGET-ORDER-1");
        verify(ctx.broker).placeExitOrder(any(), any(BrokerContext.class), eq(150.0));
    }

    private TriggerRequest optionRequest() {
        TriggerRequest request = new TriggerRequest();
        request.setInstrument("NIFTY");
        request.setExchange("NF");
        request.setStrikePrice(25000.0);
        request.setOptionType("CE");
        request.setExpiry("30/06/2026");
        request.setEntryPrice(123.45);
        request.setStopLoss(100.0);
        request.setTarget1(150.0);
        request.setQuantity(1);
        request.setUserId(9L);
        request.setBrokerCredentialsId(55L);
        request.setUseSpotForEntry(false);
        request.setUseSpotForSl(false);
        request.setUseSpotForTarget(false);
        request.setSpotScripCode(20000);
        return request;
    }

    private static class TestContext {
        private final TriggerTradeRequestRepository triggerRepo = mock(TriggerTradeRequestRepository.class);
        private final TriggeredTradeSetupRepository triggeredRepo = mock(TriggeredTradeSetupRepository.class);
        private final ScriptMasterRepository scriptRepo = mock(ScriptMasterRepository.class);
        private final BrokerCredentialsRepository brokerCredentialsRepo = mock(BrokerCredentialsRepository.class);
        private final BrokerServiceFactory brokerServiceFactory = mock(BrokerServiceFactory.class);
        private final TriggerPriceEntryBrokerService broker = mock(TriggerPriceEntryBrokerService.class);
        private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        private final WebSocketSubscriptionService subscriptionService = mock(WebSocketSubscriptionService.class);
        private final WebSocketSubscriptionHelper subscriptionHelper = mock(WebSocketSubscriptionHelper.class);
        private final AtomicReference<TriggerTradeRequestEntity> savedRequest = new AtomicReference<>();
        private final AtomicReference<TriggeredTradeSetupEntity> savedTrade = new AtomicReference<>();
        private final TradeExecutionService service;

        private TestContext(OrderPlacementResult brokerResult) {
            when(scriptRepo.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                    "NIFTY", 25000.0, "CE", "30/06/2026"))
                    .thenReturn(Optional.of(ScriptMasterEntity.builder()
                            .scripCode(123456)
                            .tradingSymbol("NIFTY")
                            .exchange("NF")
                            .instrumentType("OI")
                            .strikePrice(25000.0)
                            .optionType("CE")
                            .expiry("30/06/2026")
                            .lotSize(50)
                            .build()));
            when(triggerRepo.save(any(TriggerTradeRequestEntity.class))).thenAnswer(invocation -> {
                TriggerTradeRequestEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(77L);
                }
                savedRequest.set(entity);
                return entity;
            });
            when(triggerRepo.findById(77L)).thenAnswer(invocation -> Optional.ofNullable(savedRequest.get()));
            when(triggeredRepo.findById(88L)).thenAnswer(invocation -> Optional.ofNullable(savedTrade.get()));
            when(triggerRepo.claimIfStatusEquals(
                    77L,
                    TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                    TriggeredTradeStatus.TRIGGERED.name()))
                    .thenAnswer(invocation -> {
                        savedRequest.get().setStatus(TriggeredTradeStatus.TRIGGERED);
                        return 1;
                    });
            when(triggerRepo.claimIfStatusEquals(
                    77L,
                    TriggeredTradeStatus.TRIGGERED.name(),
                    TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name()))
                    .thenAnswer(invocation -> {
                        savedRequest.get().setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
                        return 1;
                    });
            when(triggeredRepo.save(any(TriggeredTradeSetupEntity.class))).thenAnswer(invocation -> {
                TriggeredTradeSetupEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(88L);
                }
                savedTrade.set(entity);
                return entity;
            });
            when(brokerCredentialsRepo.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                    .id(55L)
                    .brokerName(Broker.SHAREKHAN.getDisplayName())
                    .customerId(999L)
                    .apiKey("api-key")
                    .clientCode("client-code")
                    .active(true)
                    .build()));
            when(brokerServiceFactory.getService(anyString())).thenReturn(broker);
            when(broker.placeTriggerPriceEntryOrder(any(), any(BrokerContext.class), anyDouble()))
                    .thenReturn(brokerResult);

            service = new TradeExecutionService(
                    triggeredRepo,
                    triggerRepo,
                    null,
                    null,
                    null,
                    eventPublisher,
                    subscriptionService,
                    subscriptionHelper,
                    scriptRepo,
                    null,
                    null,
                    triggerRepo,
                    brokerCredentialsRepo,
                    brokerServiceFactory,
                    new OrderPlacementGuard(),
                    null
            );
        }
    }
}
