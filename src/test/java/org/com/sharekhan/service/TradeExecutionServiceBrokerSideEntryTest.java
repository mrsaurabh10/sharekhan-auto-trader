package org.com.sharekhan.service;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
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
import org.com.sharekhan.service.broker.ModifiableEntryBrokerService;
import org.com.sharekhan.service.broker.TriggerPriceEntryBrokerService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    void mcxExpiryDateRemainsTradableAfterEquityCutoff() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggerRequest request = new TriggerRequest();
        request.setInstrument("CRUDEOIL");

        LocalTime cutoff = ctx.service.optionExpiryCutoff(request);

        assertThat(cutoff).isEqualTo(LocalTime.MAX);
        assertThat(ctx.service.isTradableExpiry(
                LocalDate.of(2026, 6, 16),
                LocalDateTime.of(2026, 6, 16, 17, 0),
                cutoff)).isTrue();
    }

    @Test
    void nonMcxExpiryStillExpiresAtEquityCutoff() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggerRequest request = new TriggerRequest();
        request.setInstrument("NIFTY");
        request.setExchange("NF");

        LocalTime cutoff = ctx.service.optionExpiryCutoff(request);

        assertThat(cutoff).isEqualTo(LocalTime.of(15, 30));
        assertThat(ctx.service.isTradableExpiry(
                LocalDate.of(2026, 6, 16),
                LocalDateTime.of(2026, 6, 16, 17, 0),
                cutoff)).isFalse();
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

    @Test
    void manualTriggerPlacesEntryAtBidAskMidAndStartsEntryChase() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(true)
                .orderId("182038823")
                .status("Pending")
                .build());
        QuoteCacheService.QuoteSnapshot quote = QuoteCacheService.QuoteSnapshot.builder()
                .scripCode(123456)
                .bestBid(120.0)
                .bestAsk(130.0)
                .lastTradedPrice(124.0)
                .midPrice(125.0)
                .spreadAbsolute(10.0)
                .spreadPercent(8.0)
                .updatedAt(Instant.now())
                .build();
        when(ctx.ltpCache.getLtp(123456)).thenReturn(124.0);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(quote));
        when(ctx.quoteCache.isStale(any(), any(Duration.class))).thenReturn(false);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), eq(125.0)))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("ENTRY-ORDER-1")
                        .status("Pending")
                        .attemptedPrice(125.0)
                        .build());

        TriggeredTradeSetupEntity created = ctx.service.executeTradeFromEntity(triggerRequestEntity(), true);

        assertThat(created.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(created.getOrderId()).isEqualTo("ENTRY-ORDER-1");
        assertThat(ctx.service.isEntryOrderChaseActive(created.getId())).isTrue();
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(125.0));

        ctx.service.stopEntryOrderChase(created.getId());
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

    private TriggerTradeRequestEntity triggerRequestEntity() {
        return TriggerTradeRequestEntity.builder()
                .id(77L)
                .symbol("NIFTY")
                .scripCode(123456)
                .exchange("NF")
                .instrumentType("OI")
                .strikePrice(25000.0)
                .optionType("CE")
                .expiry("30/06/2026")
                .entryPrice(123.45)
                .stopLoss(100.0)
                .target1(150.0)
                .quantity(50L)
                .lots(1)
                .brokerCredentialsId(55L)
                .appUserId(9L)
                .useSpotForEntry(false)
                .useSpotForSl(false)
                .useSpotForTarget(false)
                .spotScripCode(20000)
                .build();
    }

    private static class TestContext {
        private final TriggerTradeRequestRepository triggerRepo = mock(TriggerTradeRequestRepository.class);
        private final TriggeredTradeSetupRepository triggeredRepo = mock(TriggeredTradeSetupRepository.class);
        private final ScriptMasterRepository scriptRepo = mock(ScriptMasterRepository.class);
        private final BrokerCredentialsRepository brokerCredentialsRepo = mock(BrokerCredentialsRepository.class);
        private final BrokerServiceFactory brokerServiceFactory = mock(BrokerServiceFactory.class);
        private final TestBrokerService broker = mock(TestBrokerService.class);
        private final LtpCacheService ltpCache = mock(LtpCacheService.class);
        private final QuoteCacheService quoteCache = mock(QuoteCacheService.class);
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
                    ltpCache,
                    quoteCache,
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

    private interface TestBrokerService extends TriggerPriceEntryBrokerService, ModifiableEntryBrokerService {
    }
}
