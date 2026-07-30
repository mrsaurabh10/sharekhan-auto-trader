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
import org.com.sharekhan.service.broker.OrderStatusBrokerService;
import org.com.sharekhan.service.broker.TriggerPriceEntryBrokerService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeExecutionServiceBrokerSideEntryTest {

    @Test
    void enablesTslForMultiLotAtrAndStockBazaariSignalsOnly() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "atr-signal", 2)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "StockBazaari", 3)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "atr-signal", 1)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "manual", 2)).isFalse();
    }

    @Test
    void usesShoonyaLiveBookForFnoEntryBeforeMstockFallback() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ScriptMasterEntity option = ScriptMasterEntity.builder()
                .scripCode(123456)
                .tradingSymbol("AUROPHARMA")
                .exchange("NF")
                .instrumentType("OI")
                .strikePrice(1560.0)
                .optionType("CE")
                .expiry("25/08/2026")
                .build();
        when(ctx.scriptRepo.findByScripCode(123456)).thenReturn(option);

        ShoonyaQuoteService shoonya = mock(ShoonyaQuoteService.class);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.of(
                new ShoonyaQuoteService.LiveQuote("AUROPHARMA25AUG26C1560", "73045", 70.50, 69.70, 71.15)));
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);

        Double price = ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveEntryReferencePrice", 123456, "executeTriggeredTrade");

        assertThat(price).isEqualTo(70.50);
        verify(ctx.ltpCache).updateLtp(123456, 70.50);
        verify(ctx.quoteCache).recordQuote(123456, 69.70, 71.15, 70.50);
    }

    @Test
    void advancesStopsForLaterInitialTargetLegsWhenEarlierTargetFills() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity targetOne = new TriggeredTradeSetupEntity();
        targetOne.setId(1L); targetOne.setTargetOrderGroupId(99L); targetOne.setTargetStage(1);
        targetOne.setActualEntryPrice(120.0); targetOne.setTarget1(140.0);

        TriggeredTradeSetupEntity targetTwo = new TriggeredTradeSetupEntity();
        targetTwo.setId(2L); targetTwo.setTargetOrderGroupId(99L); targetTwo.setTargetStage(2);
        TriggeredTradeSetupEntity targetThree = new TriggeredTradeSetupEntity();
        targetThree.setId(3L); targetThree.setTargetOrderGroupId(99L); targetThree.setTargetStage(3);
        when(ctx.triggeredRepo.findByTargetOrderGroupIdAndStatusIn(eq(99L), any()))
                .thenReturn(List.of(targetTwo, targetThree));

        ctx.service.advanceStagedTargetStops(targetOne);

        assertThat(targetTwo.getStopLoss()).isEqualTo(120.0);
        assertThat(targetThree.getStopLoss()).isEqualTo(120.0);
        verify(ctx.triggeredRepo).save(targetTwo);
        verify(ctx.triggeredRepo).save(targetThree);
    }

    @Test
    void acceptedBrokerSideEntryTriggerCreatesPendingTradeAndStartsPolling() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(true)
                .orderId("182038823")
                .status("Pending")
                .attemptedPrice(123.45)
                .build());

        TriggerTradeRequestEntity saved = ctx.service.executeTrade(optionRequest());

        assertThat(saved.getStatus()).isEqualTo(TriggeredTradeStatus.ENTRY_SUBMITTING);
        TriggeredTradeSetupEntity liveTrade = ctx.savedTrade.get();
        assertThat(liveTrade).isNotNull();
        assertThat(liveTrade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(liveTrade.getOrderId()).isEqualTo("182038823");
        assertThat(liveTrade.getEntryPrice()).isEqualTo(123.45);
        assertThat(liveTrade.getTriggerRequestId()).isEqualTo(77L);

        verify(ctx.triggerRepo).claimIfStatusEquals(
                77L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.ENTRY_SUBMITTING.name());
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
                TriggeredTradeStatus.ENTRY_SUBMITTING.name(),
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

        @SuppressWarnings("unchecked")
        Map<Long, Object> chaseStates = (Map<Long, Object>) ReflectionTestUtils.getField(ctx.service, "entryChaseStates");
        Object chaseState = chaseStates.get(created.getId());
        ReflectionTestUtils.setField(chaseState, "modifyAttempts", 10);
        Double cappedChasePrice = ReflectionTestUtils.invokeMethod(
                ctx.service, "determineEntryChasePrice", created, chaseState);
        assertThat(cappedChasePrice).isEqualTo(130.0);

        ctx.service.stopEntryOrderChase(created.getId());
    }

    @Test
    void brokerFillDetectedByFinalStatusCheckSendsExecutedTelegramAlert() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(true)
                .orderId("TRIGGER-ORDER")
                .status("Pending")
                .build());
        configureTightFreshQuote(ctx, 124.0, 124.0, 124.0);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(124.0);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("ENTRY-ORDER-1")
                        .status("Pending")
                        .attemptedPrice(124.0)
                        .build());
        JSONObject filledResponse = new JSONObject().put("data", new JSONArray().put(
                new JSONObject()
                        .put("orderStatus", "Fully Executed")
                        .put("avgPrice", 124.5)));
        when(ctx.broker.fetchOrderStatus(any(), any(BrokerContext.class), eq("ENTRY-ORDER-1")))
                .thenReturn(filledResponse);

        TriggeredTradeSetupEntity executed = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(executed).isNotNull();
        assertThat(executed.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(ctx.telegramNotificationService).sendTradeMessageForUser(
                eq(9L), eq("Order Executed ✅"), anyString());
        verify(ctx.eventPublisher, never()).publishEvent(any(OrderPlacedEvent.class));
    }

    @Test
    void entryExecutionPrefersFreshSharekhanQuoteOverCachedLtp() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 10.50, 10.60, 10.55);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(9.90);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("ENTRY-SHAREKHAN-QUOTE")
                        .status("Fully Executed")
                        .attemptedPrice(10.55)
                        .executedPrice(10.55)
                        .executedQuantity(50L)
                        .build());

        TriggeredTradeSetupEntity executed = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(executed).isNotNull();
        assertThat(executed.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(10.55));
        verify(ctx.ltpCache, never()).getLtp(123456);
    }

    @Test
    void simulatorPlacesEntryAtLtpWhenNoBidAskBookArrives() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        configureFiveAttemptPolicy(ctx);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("SIM-ENTRY-1")
                        .status("Fully Executed")
                        .attemptedPrice(10.55)
                        .executedPrice(10.55)
                        .executedQuantity(50L)
                        .build());

        TriggeredTradeSetupEntity executed = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(executed.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(10.55));
    }

    @Test
    void realBrokerStillRejectsEntryWhenNoBidAskBookArrives() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);

        TriggeredTradeSetupEntity rejected = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(rejected.getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(rejected.getExitReason()).isEqualTo("ENTRY_BOOK_UNVERIFIED");
        verify(ctx.broker, never()).placeOrder(any(), any(BrokerContext.class), anyDouble());
    }

    @Test
    void automaticEntryUsesFiveBoundedPriceLevelsThenCancels() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 10.50, 10.60, 10.55);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(pending("ENTRY-5"));
        when(ctx.broker.modifyEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-5"), anyDouble()))
                .thenReturn(pending("ENTRY-5"));
        configurePendingUntilCancelled(ctx, "ENTRY-5");

        TriggeredTradeSetupEntity result = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(result.getExitReason()).isEqualTo("ENTRY_NOT_FILLED_AFTER_5_ATTEMPTS");
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(10.55));
        ArgumentCaptor<Double> prices = ArgumentCaptor.forClass(Double.class);
        verify(ctx.broker, times(4)).modifyEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-5"), prices.capture());
        assertThat(prices.getAllValues()).containsExactly(10.60, 10.60, 10.60, 10.65);
        verify(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-5"));
    }

    @Test
    void automaticEntryCancelsWhenSpotSignalIsNoLongerValid() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        TriggerTradeRequestEntity request = triggerRequestEntity();
        request.setUseSpotForEntry(true);
        request.setOptionType("PE");
        request.setEntryPrice(434.15);
        request.setSpotScripCode(20000);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.ltpCache.getLtp(20000)).thenReturn(434.30);

        TriggeredTradeSetupEntity result = ctx.service.executeTradeFromEntity(request);

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(result.getExitReason()).isEqualTo("ENTRY_SIGNAL_INVALIDATED");
        verify(ctx.broker, never()).placeOrder(any(), any(BrokerContext.class), anyDouble());
    }

    @Test
    void automaticEntryCancelsWhenAskExceedsTwoPercentCeiling() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        QuoteCacheService.QuoteSnapshot initial = quote(10.50, 10.60, 10.55);
        QuoteCacheService.QuoteSnapshot moved = quote(10.70, 10.80, 10.75);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(initial), Optional.of(initial), Optional.of(moved));
        when(ctx.quoteCache.isStale(any(), any(Duration.class))).thenReturn(false);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(pending("ENTRY-SLIPPAGE"));
        configurePendingUntilCancelled(ctx, "ENTRY-SLIPPAGE");

        TriggeredTradeSetupEntity result = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(result.getExitReason()).isEqualTo("ENTRY_MAX_SLIPPAGE_EXCEEDED");
        verify(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-SLIPPAGE"));
        verify(ctx.broker, never()).modifyEntryOrder(any(), any(BrokerContext.class), anyString(), anyDouble());
    }

    @Test
    void partialEntryFillIsTrackedAndOnlyRemainderIsCancelled() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 10.50, 10.60, 10.55);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(pending("ENTRY-PARTIAL"));
        when(ctx.broker.modifyEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL"), anyDouble()))
                .thenReturn(pending("ENTRY-PARTIAL"));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL"));
        when(ctx.broker.fetchOrderStatus(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL")))
                .thenAnswer(invocation -> partialOrderHistory(cancelled.get() ? "Cancelled" : "Partially Executed"));

        TriggeredTradeSetupEntity result = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        assertThat(result.getQuantity()).isEqualTo(20L);
        assertThat(result.getActualEntryPrice()).isEqualTo(10.60);
        verify(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL"));
        verify(ctx.telegramNotificationService).sendTradeMessageForUser(
                eq(9L), eq("Order Executed ✅"), anyString());
    }

    @Test
    void partialEntryFillRemainsPendingWhileCancellationIsUnconfirmed() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 10.50, 10.60, 10.55);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(pending("ENTRY-PARTIAL-PENDING"));
        when(ctx.broker.modifyEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL-PENDING"), anyDouble()))
                .thenReturn(pending("ENTRY-PARTIAL-PENDING"));
        when(ctx.broker.fetchOrderStatus(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL-PENDING")))
                .thenReturn(partialOrderHistory("Partially Executed"));

        TriggeredTradeSetupEntity result = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(result.getQuantity()).isEqualTo(50L);
        verify(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL-PENDING"));
        verify(ctx.eventPublisher).publishEvent(any(OrderPlacedEvent.class));
        verify(ctx.telegramNotificationService, never()).sendTradeMessageForUser(
                eq(9L), eq("Order Executed ✅"), anyString());
    }

    @Test
    void pollingTreatsTerminalCancellationWithFillAsPartialExecution() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(88L)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .quantity(50L)
                .lots(1)
                .entryPrice(10.55)
                .stopLoss(9.50)
                .target1(12.00)
                .useSpotForEntry(false)
                .build();

        TradeExecutionService.TradeStatus status = ctx.service.evaluateOrderFinalStatus(
                trade, partialOrderHistory("Cancelled"));

        assertThat(status).isEqualTo(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        assertThat(trade.getQuantity()).isEqualTo(20L);
        assertThat(trade.getLots()).isNull();
        assertThat(trade.getActualEntryPrice()).isEqualTo(10.60);
    }

    private static OrderPlacementResult pending(String orderId) {
        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status("Pending")
                .build();
    }

    private static JSONObject orderHistory(String status) {
        return new JSONObject().put("data", new JSONArray().put(
                new JSONObject().put("orderStatus", status)));
    }

    private static JSONObject partialOrderHistory(String status) {
        return new JSONObject().put("data", new JSONArray().put(
                new JSONObject()
                        .put("orderStatus", status)
                        .put("filledQty", 20)
                        .put("pendingQty", 30)
                        .put("avgPrice", 10.60)));
    }

    private static void configurePendingUntilCancelled(TestContext ctx, String orderId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(ctx.broker).cancelEntryOrder(any(), any(BrokerContext.class), eq(orderId));
        when(ctx.broker.fetchOrderStatus(any(), any(BrokerContext.class), eq(orderId)))
                .thenAnswer(invocation -> orderHistory(cancelled.get() ? "Cancelled" : "Pending"));
    }

    private static void configureFiveAttemptPolicy(TestContext ctx) {
        ReflectionTestUtils.setField(ctx.service, "entryMaxSpreadPercent", 1.5d);
        ReflectionTestUtils.setField(ctx.service, "entryQuoteStaleMillis", 2000L);
        ReflectionTestUtils.setField(ctx.service, "entryMaxAttempts", 5);
        ReflectionTestUtils.setField(ctx.service, "entryRetryDelayMillis", 1L);
        ReflectionTestUtils.setField(ctx.service, "entryMaxSlippagePercent", 2.0d);
        ReflectionTestUtils.setField(ctx.service, "entryHardSpreadPercent", 2.5d);
        ReflectionTestUtils.setField(ctx.service, "entryWideSpreadConfirmations", 2);
    }

    private static void configureTightFreshQuote(TestContext ctx, double bid, double ask, double mid) {
        QuoteCacheService.QuoteSnapshot quote = quote(bid, ask, mid);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(quote));
        when(ctx.quoteCache.isBookStale(any(), any(Duration.class))).thenReturn(false);
    }

    private static QuoteCacheService.QuoteSnapshot quote(double bid, double ask, double mid) {
        return QuoteCacheService.QuoteSnapshot.builder()
                .scripCode(123456)
                .bestBid(bid)
                .bestAsk(ask)
                .lastTradedPrice(mid)
                .midPrice(mid)
                .spreadAbsolute(ask - bid)
                .spreadPercent((ask - bid) * 100d / mid)
                .lastLtpAt(Instant.now())
                .lastBookAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
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
        private final TelegramNotificationService telegramNotificationService = mock(TelegramNotificationService.class);
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
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                    .thenAnswer(invocation -> {
                        savedRequest.get().setStatus(TriggeredTradeStatus.ENTRY_SUBMITTING);
                        return 1;
                    });
            when(triggerRepo.claimIfStatusEquals(
                    77L,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(),
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
            ReflectionTestUtils.setField(service, "telegramNotificationService", telegramNotificationService);
        }

        private void useSimulatorBroker() {
            when(brokerCredentialsRepo.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                    .id(55L)
                    .brokerName(Broker.SIMULATOR.getDisplayName())
                    .customerId(999L)
                    .apiKey("api-key")
                    .clientCode("client-code")
                    .active(true)
                    .build()));
        }
    }

    private interface TestBrokerService extends TriggerPriceEntryBrokerService,
            ModifiableEntryBrokerService, OrderStatusBrokerService {
    }
}
