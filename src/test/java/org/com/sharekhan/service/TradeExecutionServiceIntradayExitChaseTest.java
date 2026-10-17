package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.broker.*;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeExecutionServiceIntradayExitChaseTest {
    private final TriggeredTradeSetupRepository trades = mock(TriggeredTradeSetupRepository.class);
    private final ScriptMasterRepository scripts = mock(ScriptMasterRepository.class);
    private final BrokerCredentialsRepository credentials = mock(BrokerCredentialsRepository.class);
    private final BrokerServiceFactory brokers = mock(BrokerServiceFactory.class);
    private final TestBroker broker = mock(TestBroker.class);
    private final QuoteCacheService quotes = spy(new QuoteCacheService());
    private final LtpCacheService ltps = mock(LtpCacheService.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final TelegramNotificationService notifications = mock(TelegramNotificationService.class);
    private final TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
            .id(10265L).symbol("BHARATFORG").scripCode(123).exchange("NF")
            .appUserId(1L).brokerCredentialsId(55L).intraday(true)
            .quantity(500L).actualEntryPrice(48.1).stopLoss(39.1)
            .exitOrderId("211652102").exitReason("TARGET_ORDER_AUTO")
            .status(TriggeredTradeStatus.TARGET_ORDER_PLACED).build();
    private TradeExecutionService service;
    private Runnable tick;
    private final Instant start = ZonedDateTime.of(2026, 10, 15, 15, 35, 0, 0,
            ZoneId.of("Asia/Kolkata")).toInstant();

    @BeforeEach
    void setup() {
        service = new TradeExecutionService(trades, null, null, ltps, quotes, null,
                null, null, scripts, null, null, null,
                credentials, brokers, new OrderPlacementGuard(), null);
        ReflectionTestUtils.setField(service, "exitChaseScheduler", scheduler);
        ReflectionTestUtils.setField(service, "telegramNotificationService", notifications);
        at(start);
        when(trades.findById(trade.getId())).thenReturn(Optional.of(trade));
        when(credentials.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                .id(55L).appUserId(1L).brokerName("Shoonya").customerId(999L).active(true).build()));
        when(brokers.getService(anyString())).thenReturn(broker);
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Open"));
        when(broker.modifyEntryOrder(any(), any(), anyString(), anyDouble()))
                .thenReturn(OrderPlacementResult.builder().success(true).orderId("211652102").build());
        doAnswer(invocation -> { tick = invocation.getArgument(0); return future; })
                .when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1500L), eq(TimeUnit.MILLISECONDS));
        quotes.recordQuote(123, 42.0, 42.2, 42.1);
    }

    @Test
    void failedStopModifyKeepsSameOrderAndRetriesUntilFilled() {
        when(broker.modifyEntryOrder(any(), any(), anyString(), anyDouble()))
                .thenReturn(OrderPlacementResult.builder().success(false).build())
                .thenReturn(OrderPlacementResult.builder().success(false).build())
                .thenReturn(OrderPlacementResult.builder().success(true).build());
        assertThat(service.modifyExitOrderForStop(trade, 38.9)).isFalse();
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        tick.run();
        verify(future, never()).cancel(anyBoolean());
        tick.run();
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Fully Executed"));
        tick.run();
        verify(broker, times(3)).modifyEntryOrder(eq(trade), any(), eq("211652102"), anyDouble());
        verify(future).cancel(true);
        assertThat(trade.getExitOrderId()).isEqualTo("211652102");
        assertThat(trade.getExitReason()).isEqualTo("STOP_LOSS_HIT");
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void squareOffReusesExistingSellInsteadOfPlacingAnother() {
        trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
        service.squareOff(trade, 38.9, "STOP_LOSS_HIT");
        verify(broker).modifyEntryOrder(eq(trade), any(), eq("211652102"), eq(38.9));
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
        verify(trades, never()).claimIfStatusEquals(anyLong(), anyString(), anyString(), anyString());
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }

    @Test
    void stopChaseWaitsOnUnknownStatusAndStaleBookAndStopsAtDeadline() {
        service.modifyExitOrderForStop(trade, 38.9);
        clearInvocations(broker);
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(null);
        tick.run();
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Open"));
        doReturn(true).when(quotes).isBookStale(any(), any());
        tick.run();
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        at(start.plusSeconds(300));
        tick.run();
        verify(future).cancel(true);
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void cancelledStopOrderNeverCreatesReplacementSell() {
        service.modifyExitOrderForStop(trade, 38.9);
        clearInvocations(broker);
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Cancelled"));
        tick.run();
        verify(future).cancel(true);
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void placementGuardRejectsExistingSellEvenWhenCalledDirectly() {
        TriggeredTradeSetupEntity stale = TriggeredTradeSetupEntity.builder()
                .id(trade.getId()).status(TriggeredTradeStatus.EXIT_TRIGGERED).build();
        OrderPlacementResult result = ReflectionTestUtils.invokeMethod(service,
                "placeExitOrderAndPersistWithoutLock", stale, 38.9, "STOP_LOSS_HIT",
                TriggeredTradeStatus.EXIT_ORDER_PLACED);
        assertThat(result.isSuccess()).isFalse();
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void partialStopFillKeepsOriginalOrderAndQuantityDuringChase() {
        service.modifyExitOrderForStop(trade, 38.9);
        clearInvocations(broker);
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Partially Executed"));
        tick.run();
        verify(broker).modifyEntryOrder(eq(trade), any(), eq("211652102"), eq(42.0));
        assertThat(trade.getQuantity()).isEqualTo(500L);
        assertThat(trade.getExitReason()).isEqualTo("STOP_LOSS_HIT");
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void terminalTradeCannotBeReopenedBySquareOffWithOldExitId() {
        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        service.squareOff(trade, 38.9, "STOP_LOSS_HIT");
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXITED_SUCCESS);
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    private void at(Instant now) {
        ReflectionTestUtils.setField(service, "exitChaseClock", Clock.fixed(now, ZoneOffset.UTC));
    }

    private static JSONObject history(String status) {
        return new JSONObject().put("data", new JSONArray().put(
                new JSONObject().put("orderStatus", status).put("avgPrice", 42.0)));
    }

    @Test
    void doesNotRouteAnExitThroughAnotherUsersBroker() {
        when(credentials.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                .id(55L).appUserId(2L).brokerName("Shoonya").customerId(999L).active(true).build()));
        assertThat(service.startIntradayExitChase(trade)).isFalse();
        verifyNoInteractions(brokers);
    }

    @Test
    void chasesTheExistingOrderAtChangingBidsUntilFilledWithoutAdvancingTargetStops() {
        assertThat(service.startIntradayExitChase(trade)).isTrue();
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        tick.run();
        quotes.recordQuote(123, 41.8, 42.0, 42.1);
        tick.run();
        when(broker.fetchOrderStatus(any(), any(), eq("211652102"))).thenReturn(history("Fully Executed"));
        tick.run();
        ArgumentCaptor<Double> prices = ArgumentCaptor.forClass(Double.class);
        verify(broker, times(2)).modifyEntryOrder(eq(trade), any(), eq("211652102"), prices.capture());
        assertThat(prices.getAllValues()).containsExactly(42.0, 41.8);
        assertThat(trade.getExitReason()).isEqualTo("INTRADAY_CLOSE");
        verify(future).cancel(true);
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
        service.advanceStagedTargetStopsAfterTargetExit(trade);
        verify(trades, never()).findByTargetOrderGroupIdAndStatusIn(any(), any());
    }

    @Test
    void missingOrStaleBookWaitsForFreshBidEvenWithCachedLtpAndStopLoss() {
        assertThat(service.startIntradayExitChase(trade)).isTrue();
        doReturn(true).when(quotes).isBookStale(any(), any());
        when(ltps.getLtp(123)).thenReturn(42.1);
        tick.run();
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        doReturn(false).when(quotes).isBookStale(any(), any());
        tick.run();
        verify(broker).modifyEntryOrder(eq(trade), any(), eq("211652102"), eq(42.0));
    }

    @Test
    void unknownBrokerStatusWaitsRatherThanModifyingOrReplacingTheOrder() {
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(null);
        service.startIntradayExitChase(trade);
        tick.run();
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void unchangedBidDoesNotFloodTheBroker() {
        service.startIntradayExitChase(trade);
        tick.run(); tick.run();
        verify(broker).modifyEntryOrder(any(), any(), eq("211652102"), eq(42.0));
    }

    @Test
    void modificationRejectionStopsAndReportsUnconfirmedClosure() {
        when(broker.modifyEntryOrder(any(), any(), anyString(), anyDouble()))
                .thenReturn(OrderPlacementResult.builder().success(false).build());
        service.startIntradayExitChase(trade);
        tick.run();
        verify(future).cancel(true);
        verify(notifications).sendTradeMessageForUser(eq(1L), anyString(), contains("did not accept"));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        assertThat(trade.getExitedAt()).isNull();
    }

    @Test
    void cancelledBrokerOrderStopsWithoutCreatingADuplicateSell() {
        when(broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(history("Cancelled"));
        service.startIntradayExitChase(trade);
        tick.run();
        verify(future).cancel(true);
        verify(notifications).sendTradeMessageForUser(eq(1L), anyString(), contains("REJECTED"));
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        verify(broker, never()).placeExitOrder(any(), any(), anyDouble());
    }

    @Test
    void deadlineStopsAndAlertsWithoutMarkingThePositionClosed() {
        service.startIntradayExitChase(trade);
        at(start.plusSeconds(300));
        tick.run();
        verify(future).cancel(true);
        verify(notifications).sendTradeMessageForUser(eq(1L), anyString(), contains("deadline"));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        assertThat(trade.getExitedAt()).isNull();
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    @Test
    void orderReplacementOrTerminalDatabaseStateStopsTheOldChase() {
        service.startIntradayExitChase(trade);
        trade.setExitOrderId("replacement");
        tick.run();
        verify(future).cancel(true);
        verify(broker, never()).fetchOrderStatus(any(), any(), anyString());
    }

    @Test
    void refusesToStartAfterSessionCloseOrForDeliveryPositions() {
        trade.setIntraday(false);
        assertThat(service.startIntradayExitChase(trade)).isFalse();
        trade.setIntraday(true);
        at(start.plusSeconds(301));
        assertThat(service.startIntradayExitChase(trade)).isFalse();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.TARGET_ORDER_PLACED);
    }

    @Test
    void sharekhanModificationRequiresMatchingOrderAcknowledgementAndNoError() {
        JSONObject accepted = new JSONObject().put("status", 200).put("data",
                new JSONObject().put("orderId", "211652102"));
        assertThat(ShareKhanOrderUtil.isModifyAccepted(accepted, "211652102")).isTrue();
        assertThat(ShareKhanOrderUtil.isModifyAccepted(accepted, "another-order")).isFalse();
        accepted.put("status", 400);
        assertThat(ShareKhanOrderUtil.isModifyAccepted(accepted, "211652102")).isFalse();
        assertThat(ShareKhanOrderUtil.isModifyAccepted(new JSONObject(), "211652102")).isFalse();
        assertThat(ShareKhanOrderUtil.isModifyAccepted(null, "211652102")).isFalse();
        accepted.put("status", 200).getJSONObject("data").put("errormsg", "rejected");
        assertThat(ShareKhanOrderUtil.isModifyAccepted(accepted, "211652102")).isFalse();
    }

    @Test
    void realSharekhanModifyPathRejectsErrorPayloadAndAcceptsMatchingAcknowledgement() {
        service = spy(service);
        var tokens = mock(org.com.sharekhan.auth.TokenStoreService.class);
        ReflectionTestUtils.setField(service, "tokenStoreService", tokens);
        when(tokens.getAccessToken(org.com.sharekhan.enums.Broker.SHAREKHAN, 999L)).thenReturn("test-token");
        when(credentials.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                .id(55L).appUserId(1L).brokerName("Sharekhan").apiKey("test-key").customerId(999L).active(true).build()));
        when(scripts.findById(123)).thenReturn(Optional.of(org.com.sharekhan.entity.ScriptMasterEntity.builder()
                .scripCode(123).exchange("NF").optionType("CE").tickSize(0.05).build()));
        var client = mock(com.sharekhan.SharekhanConnect.class);
        doReturn(client).when(service).createSharekhanExitClient("test-key", "test-token");
        when(client.modifyorder(any())).thenReturn(new JSONObject().put("status", 400).put("message", "rejected"));
        assertThat(service.modifyExitOrderPrice(trade.getId(), 42.0, "INTRADAY_CLOSE").isSuccess()).isFalse();
        verify(trades, never()).save(any());
        assertThat(trade.getExitReason()).isEqualTo("TARGET_ORDER_AUTO");
        when(client.modifyorder(any())).thenReturn(new JSONObject().put("status", 200)
                .put("data", new JSONObject().put("orderId", "211652102")));
        assertThat(service.modifyExitOrderPrice(trade.getId(), 42.0, "INTRADAY_CLOSE").isSuccess()).isTrue();
        verify(trades).save(trade);
        assertThat(trade.getExitReason()).isEqualTo("INTRADAY_CLOSE");
        var params = ArgumentCaptor.forClass(com.sharekhan.model.OrderParams.class);
        verify(client, times(2)).modifyorder(params.capture());
        assertThat(params.getValue().orderId).isEqualTo("211652102");
        assertThat(params.getValue().transactionType).isEqualTo("S");
        assertThat(params.getValue().quantity).isEqualTo(500L);
    }

    @Test
    void fiveMinuteBudgetStopsTheChaseBeforeSessionClose() {
        Instant earlier = start.minusSeconds(300);
        at(earlier);
        service.startIntradayExitChase(trade);
        at(start);
        tick.run();
        verify(future).cancel(true);
        verify(notifications).sendTradeMessageForUser(eq(1L), anyString(), contains("deadline"));
        verify(broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    private interface TestBroker extends ModifiableEntryBrokerService, OrderStatusBrokerService { }
}
