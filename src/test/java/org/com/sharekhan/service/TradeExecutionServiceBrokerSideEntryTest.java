package org.com.sharekhan.service;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.EntryChaseControl;
import org.com.sharekhan.repository.EntryChaseControlRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void immediateSimulatorCashWaitIsExplicitNoOrderRatherThanExecutionUnavailable() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        var service = ctx.service;
        var saved = triggerRequestEntity();
        saved.setSymbol("UNIONBANK"); saved.setExchange("NC"); saved.setOptionType(null);
        saved.setStrikePrice(null); saved.setExpiry(null); saved.setEntryPrice(176.64);
        saved.setSource("strategy:STOCK_0925_MOVER_ATR_BREAKOUT");
        saved.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        ctx.savedRequest.set(saved);
        when(ctx.scriptRepo.findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull("NC", "UNIONBANK"))
                .thenReturn(Optional.of(ScriptMasterEntity.builder().scripCode(123456).exchange("NC").tradingSymbol("UNIONBANK").build()));
        when(ctx.ltpCache.getLtp(123456)).thenReturn(176.50);
        when(ctx.triggerRepo.claimIfStatusEquals(77L, "PLACED_PENDING_CONFIRMATION", "TRIGGERED")).thenReturn(1);
        when(ctx.triggerRepo.claimIfStatusEqualsWithOutcome(eq(77L), eq("TRIGGERED"), eq("REJECTED"),
                eq(org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON), anyString())).thenReturn(1);
        TriggerRequest input = new TriggerRequest(); input.setInstrument("UNIONBANK"); input.setExchange("NC");
        input.setEntryPrice(176.64); input.setQuantity(11); input.setBrokerCredentialsId(55L); input.setUserId(9L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executeTriggeredTrade(input))
                .isInstanceOf(org.com.sharekhan.exception.EntryNotReadyException.class).hasMessageContaining("buy trigger");
        assertThat(ctx.savedRequest.get().getReason()).isEqualTo(org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON);
        assertThat(ctx.savedRequest.get().getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        verify(ctx.broker, never()).placeOrder(any(), any(), anyDouble());
        verify(ctx.triggeredRepo, never()).save(any());
    }

    @Test
    void missingSimulatorOptionPriceWaitsBeforeSavingAnyRequest() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.service.executeTriggeredTrade(optionRequest()))
                .isInstanceOf(org.com.sharekhan.exception.EntryNotReadyException.class)
                .hasMessageContaining("no request or order submitted");
        verify(ctx.triggerRepo, never()).save(any());
        verify(ctx.broker, never()).placeOrder(any(), any(), anyDouble());
    }

    @Test
    void simulatorQueuedWaitStillStaysPendingAndNeverFillsBelowTrigger() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        var request = triggerRequestEntity(); request.setEntryPrice(20d);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10d);
        assertThat(ctx.service.executeTradeFromEntity(request)).isNull();
        verify(ctx.broker, never()).placeOrder(any(), any(), anyDouble());
        verify(ctx.triggeredRepo, never()).save(any());
    }

    @Test
    void dhanContextRequiresScopedClientIdentityButNotSharekhanCustomerOrApiKey() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        var dhan = new BrokerContext(null,null,"1000000001","Dhan",55L);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(ctx.service,"isBrokerContextUsable",dhan)).isTrue();
        dhan.setBrokerCredentialsId(null);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(ctx.service,"isBrokerContextUsable",dhan)).isFalse();
    }

    @Test
    void entryRejectionAuditPreservesSpotReferenceAndObservedTime() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TradeAuditService audit = mock(TradeAuditService.class);
        ReflectionTestUtils.setField(ctx.service, "tradeAuditService", audit);
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setSpotScripCode(20000);
        trade.setEntryPrice(22697.9);
        when(ctx.ltpCache.getLtp(20000)).thenReturn(22657.8);
        java.time.LocalDateTime observedAt = java.time.LocalDateTime.of(2026, 10, 7, 10, 30);
        when(ctx.ltpCache.getObservedAt(20000)).thenReturn(observedAt);
        ReflectionTestUtils.invokeMethod(ctx.service, "auditEntryOutcome", trade,
                "ENTRY_REJECTED", "ENTRY_SIGNAL_INVALIDATED", null, null);
        org.mockito.ArgumentCaptor<org.com.sharekhan.entity.TradeAuditEventEntity> event =
                org.mockito.ArgumentCaptor.forClass(org.com.sharekhan.entity.TradeAuditEventEntity.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getSpotPrice()).isEqualTo(22657.8);
        assertThat(event.getValue().getDetails()).contains("signalClose=22697.9", "spotObservedAt=" + observedAt);
    }

    @ParameterizedTest
    @CsvSource({
            "awr, 105, 110", "AWR, 95, 110",
            "StockBazaari, 105, 110", "stockbazaari, 95, 110",
            "manual, 105, 115", "manual, 95, 105",
            "Sharekhan, 105, 110"
    })
    void appliesSourceTargetPolicyForImmediateAndConfirmedFills(String source, double fill, double target) {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        for (String method : List.of("applyImmediateEntryExecution", "applyConfirmedEntryPrice")) {
            TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
            trade.setSource(source);
            trade.setEntryPrice(100.0);
            trade.setStopLoss(90.0);
            trade.setTarget1(110.0);
            trade.setTarget2(120.0);
            trade.setTarget3(130.0);
            trade.setUseSpotForEntry(false);

            if ("applyImmediateEntryExecution".equals(method)) {
                ReflectionTestUtils.invokeMethod(ctx.service, method, trade,
                        OrderPlacementResult.builder().success(true).executedPrice(fill).build());
            } else {
                ReflectionTestUtils.invokeMethod(ctx.service, method, trade, fill);
            }

            assertThat(trade.getTarget1()).as(method).isEqualTo(target);
            assertThat(trade.getTarget2()).as(method).isEqualTo(target + 10);
            assertThat(trade.getTarget3()).as(method).isEqualTo(target + 20);
            assertThat(trade.getActualEntryPrice()).isEqualTo(fill);
            boolean sharekhan = "Sharekhan".equalsIgnoreCase(source);
            assertThat(trade.getEntryPrice()).isEqualTo(sharekhan ? 100.0 : fill);
            assertThat(trade.getStopLoss()).isEqualTo(sharekhan ? 90.0 : fill - 10);
        }
    }

    @Test
    void enablesTslForMultiLotAtrAndStockBazaariSignals() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "atr-signal", 2, null, null)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "StockBazaari", 3, null, null)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "atr-signal", 1, null, null)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveTslEnabled", false, "manual", 2, null, null)).isFalse();
    }

    @Test
    void supertrendTslUsesResolvedLotsIncludingDefaultsAndPartialFills() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        for (String source : List.of("strategy:ST_RSI_EMA_ADX_CE", "strategy:ST_RSI_EMA_ADX_PE")) {
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                    ctx.service, "resolveTslEnabled", false, source, 3, 120.0, 130.0)).isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                    ctx.service, "resolveTslEnabled", null, source, 2, 120.0, 130.0)).isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                    ctx.service, "resolveTslEnabled", true, source, 1, 120.0, 130.0)).isFalse();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                    ctx.service, "resolveTslEnabled", false, source, null, 120.0, 130.0)).isFalse();
        }
    }

    @Test
    void stagesAnyTslEquityTargetsByShareQuantity() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setId(1L);
        trade.setSource("manual");
        trade.setExchange("NC");
        trade.setQuantity(120L);
        trade.setTslEnabled(true);
        trade.setTarget1(110.0);
        trade.setTarget2(120.0);
        trade.setTarget3(130.0);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                ctx.service, "isEligibleForStagedTargetOrders", trade)).isTrue();
        assertThat((Integer) ReflectionTestUtils.invokeMethod(ctx.service, "resolveLots", trade)).isEqualTo(120);
    }

    @Test
    void allocatesEquityRemainderToFinalTarget() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());

        @SuppressWarnings("unchecked")
        List<Integer> allocation = ReflectionTestUtils.invokeMethod(ctx.service, "splitLots", 200, 3);

        assertThat(allocation).containsExactly(66, 66, 68);
    }

    @Test void shortSlippageRejectsLowerPricesAndTargetChildrenRetainSellDirection() {
        TestContext ctx=new TestContext(OrderPlacementResult.builder().success(true).build());
        ReflectionTestUtils.setField(ctx.service,"entryMaxSlippagePercent",2d);
        var root=TriggeredTradeSetupEntity.builder().id(1L).exchange("NC").entryAction("SELL").originalLots(13).quantity(13L)
                .intraday(true).brokerExecutionProduct("INTRADAY").build();
        Boolean tooLow=ReflectionTestUtils.invokeMethod(ctx.service,"isEntrySlippageLimitReached",root,97d,100d);
        Boolean acceptable=ReflectionTestUtils.invokeMethod(ctx.service,"isEntrySlippageLimitReached",root,101d,100d);
        assertThat(tooLow).isTrue(); assertThat(acceptable).isFalse();
        TriggeredTradeSetupEntity leg=ReflectionTestUtils.invokeMethod(ctx.service,"copyTargetLeg",root,1L,2,4,95d,13L);
        assertThat(leg.getEntryAction()).isEqualTo("SELL");
        assertThat(leg.getQuantity()).isEqualTo(4L);
        assertThat(leg.getBrokerExecutionProduct()).isEqualTo("INTRADAY");
    }

    @Test void stagedTargetsRetainBrokerExecutionProductAndAppManagedSameDayExit() {
        TestContext ctx=new TestContext(OrderPlacementResult.builder().success(true).build());
        var root=TriggeredTradeSetupEntity.builder().id(1L).originalLots(3).quantity(195L)
                .intraday(true).brokerExecutionProduct("CNC").build();
        TriggeredTradeSetupEntity leg=ReflectionTestUtils.invokeMethod(ctx.service,"copyTargetLeg",root,1L,2,1,210d,195L);
        assertThat(leg.getBrokerExecutionProduct()).isEqualTo("CNC");
        assertThat(leg.getIntraday()).isTrue();
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
    void rejectsShoonyaOptionQuoteWhenReturnedIdentityIsNotTheRequestedContract() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ScriptMasterEntity option = ScriptMasterEntity.builder()
                .scripCode(123456).tradingSymbol("AUROPHARMA").exchange("NF")
                .instrumentType("OI").strikePrice(1560.0).optionType("CE").expiry("25/08/2026").build();
        when(ctx.scriptRepo.findByScripCode(123456)).thenReturn(option);

        ShoonyaQuoteService shoonya = mock(ShoonyaQuoteService.class);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.of(new ShoonyaQuoteService.LiveQuote(
                "AUROPHARMA25AUG26C1560", "73045", "AUROPHARMA-EQ", "275", 1560.0, 1559.0, 1561.0)));
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);

        Double price = ReflectionTestUtils.invokeMethod(
                ctx.service, "resolveEntryReferencePrice", 123456, "executeTriggeredTrade");

        assertThat(price).isNull();
        verify(ctx.ltpCache, never()).updateLtp(123456, 1560.0);
    }

    @Test
    void retriesWrongIdentityBeforeUsingVerifiedPremiumWithoutSubmittingAnyOrder() {
        var ctx = new TestContext(pending("unused"));
        var option = retryOption(ctx);
        var shoonya = mock(ShoonyaQuoteService.class);
        var wrong = new ShoonyaQuoteService.LiveQuote("SENSEX26O2273200CE", "1124326",
                "SENSEX", "1", 73164.81, null, null);
        var correct = new ShoonyaQuoteService.LiveQuote("SENSEX26O2273200CE", "1124326", 125.6, 125.15, 125.5);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.of(wrong), Optional.of(correct));
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
        Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456, "executeTradeFromEntity");
        assertThat(price).isEqualTo(125.6);
        verify(shoonya, times(2)).getOptionQuote(option);
        verify(ctx.ltpCache, never()).updateLtp(123456, 73164.81);
        verify(ctx.ltpCache).updateLtp(123456, 125.6);
        org.mockito.Mockito.verifyNoInteractions(ctx.broker);
    }

    @Test
    void exhaustedQuoteReadsAreBoundedAndDoNotSubmitOrders() {
        var ctx = new TestContext(pending("unused")); var option = retryOption(ctx);
        var shoonya = mock(ShoonyaQuoteService.class);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
        Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456, "executeTradeFromEntity");
        assertThat(price).isNull(); verify(shoonya, times(3)).getOptionQuote(option);
        org.mockito.Mockito.verifyNoInteractions(ctx.broker);
    }

    @Test
    void retriesStopWhenEntryWindowClosesDuringAQuoteRead() {
        var ctx = new TestContext(pending("unused")); var option = retryOption(ctx);
        var shoonya = mock(ShoonyaQuoteService.class);
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(shoonya.getOptionQuote(option)).thenAnswer(i -> { open.set(false); return Optional.empty(); });
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
        Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456,
                "executeTradeFromEntity", (java.util.function.BooleanSupplier) open::get);
        assertThat(price).isNull(); verify(shoonya).getOptionQuote(option);
        org.mockito.Mockito.verifyNoInteractions(ctx.broker);
    }

    @Test
    void failedFinalPremiumReadPersistsSpecificNoOrderReason() {
        var ctx = new TestContext(pending("unused"));
        var option = retryOption(ctx); var shoonya = mock(ShoonyaQuoteService.class);
        var correct = new ShoonyaQuoteService.LiveQuote("SENSEX26O2273200CE", "1124326", 125.6, 125.15, 125.5);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.of(correct), Optional.empty());
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
        when(ctx.triggerRepo.claimIfStatusEquals(77L, "PLACED_PENDING_CONFIRMATION", "TRIGGERED")).thenReturn(1);
        when(ctx.triggerRepo.claimIfStatusEqualsWithOutcome(eq(77L), eq("TRIGGERED"), eq("REJECTED"),
                eq("ENTRY_OPTION_QUOTE_UNAVAILABLE"), anyString())).thenReturn(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.service.executeTriggeredTrade(optionRequest()))
                .hasMessageContaining("no broker order submitted");
        assertThat(ctx.savedRequest.get().getReason()).isEqualTo("ENTRY_OPTION_QUOTE_UNAVAILABLE");
        assertThat(ctx.savedRequest.get().getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        verify(ctx.triggerRepo).claimIfStatusEqualsWithOutcome(eq(77L), eq("TRIGGERED"), eq("REJECTED"),
                eq("ENTRY_OPTION_QUOTE_UNAVAILABLE"), anyString());
        org.mockito.Mockito.verifyNoInteractions(ctx.broker);
    }

    @Test
    void recoveredFinalQuoteSubmitsExactlyOneBrokerOrder() {
        var ctx = new TestContext(pending("unused")); var option = retryOption(ctx);
        configureFiveAttemptPolicy(ctx);
        var shoonya = mock(ShoonyaQuoteService.class);
        var correct = new ShoonyaQuoteService.LiveQuote("NIFTY30JUN26C25000", "123456", 125.6, 125.15, 125.5);
        var wrong = new ShoonyaQuoteService.LiveQuote("NIFTY30JUN26C25000", "123456", "Nifty 50", "26000", 23000d, null, null);
        when(shoonya.getOptionQuote(option)).thenReturn(Optional.of(correct), Optional.of(wrong), Optional.of(correct));
        ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
        when(ctx.triggerRepo.claimIfStatusEquals(77L, "PLACED_PENDING_CONFIRMATION", "TRIGGERED")).thenReturn(1);
        when(ctx.broker.placeOrder(any(), any(), anyDouble())).thenReturn(OrderPlacementResult.builder()
                .success(true).orderId("single-order").status("Fully Executed").executedPrice(125.6).build());
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(quote(125.15, 125.5, 125.325)));
        when(ctx.ltpCache.getLtp(123456)).thenReturn(125.6);
        var request = ctx.service.executeTriggeredTrade(optionRequest());
        assertThat(request.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(ctx.broker, times(1)).placeOrder(any(), any(), anyDouble());
        verify(shoonya, times(3)).getOptionQuote(option);
        verify(ctx.ltpCache, never()).updateLtp(123456, 23000d);
    }

    @Test
    void finalSubmissionGuardBlocksAnExpiredStraddleEvenWithAVerifiedBook() {
        var ctx = new TestContext(pending("unused")); configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 125.15, 125.5, 125.325);
        var trade = TriggeredTradeSetupEntity.builder().scripCode(123456).quantity(50L)
                .entryPrice(125.6).source("expiry-straddle:2000-01-01:9:55:NIFTY:AM").build();
        OrderPlacementResult result = ReflectionTestUtils.invokeMethod(ctx.service, "attemptEntryPlacement",
                trade, 125.6, new BrokerContext(), ctx.broker, false);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getRejectionReason()).contains("ENTRY_WINDOW_ELAPSED");
        org.mockito.Mockito.verifyNoInteractions(ctx.broker);
    }

    private TriggeredTradeSetupEntity averagingTrade(String side,double admitted) {
        return TriggeredTradeSetupEntity.builder().scripCode(123456).symbol("NATURALGAS").exchange("MX")
                .quantity(1250L).lots(1).entryAction("BUY").optionType(side).entryPrice(admitted)
                .source(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.SOURCE).build();
    }
    private QuoteCacheService.QuoteSnapshot averagingQuote(double bid,double ask,Instant at) {
        return QuoteCacheService.QuoteSnapshot.builder().bestBid(bid).bestAsk(ask).midPrice((bid+ask)/2).spreadPercent((ask-bid)/ask*100).lastTradedPrice(ask)
                .lastBookAt(at).updatedAt(at).coherentBook(true).bookSource("SHAREKHAN_WS")
                .bookProviderIdentity("scrip:123456").build();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"CE","PE"})
    void averagingPlacementUsesExecutableAskThroughRealSimulator(String side) {
        var ctx=new TestContext(pending("unused"));configureFiveAttemptPolicy(ctx);
        ReflectionTestUtils.setField(ctx.service, "entryClock", java.time.Clock.fixed(
                java.time.ZonedDateTime.of(2026,10,28,12,0,0,0,java.time.ZoneId.of("Asia/Kolkata")).toInstant(), java.time.ZoneId.of("Asia/Kolkata")));
        var cache=new QuoteCacheService();cache.recordQuote(123456,20.3,20.4,20.4,"SHAREKHAN_WS",null,"scrip:123456",null,null);
        ReflectionTestUtils.setField(ctx.service,"quoteCacheService",cache);
        var simulator=new org.com.sharekhan.service.broker.SimulatorBrokerService();ReflectionTestUtils.setField(simulator,"quotes",cache);
        var context=new BrokerContext();context.setBrokerName("Simulator");
        OrderPlacementResult result=ReflectionTestUtils.invokeMethod(ctx.service,"attemptEntryPlacement",averagingTrade(side,20.4),20.4,context,simulator,false);
        assertThat(result.isSuccess()).isTrue();assertThat(result.getExecutedPrice()).isEqualTo(20.4);
        assertThat(result.getAttemptedPrice()).isEqualTo(20.4);
    }
    @Test void averagingPlacementWaitsIfAskRisesBeyondOriginalAdmission() {
        var ctx=new TestContext(pending("unused"));configureFiveAttemptPolicy(ctx);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(averagingQuote(20.4,20.45,Instant.now())));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(ctx.service,"attemptEntryPlacement",
                averagingTrade("CE",20.4),20.4,new BrokerContext(),ctx.broker,false))
                .isInstanceOf(org.com.sharekhan.exception.EntryNotReadyException.class).hasMessageContaining("originally admitted");
        verify(ctx.broker,never()).placeOrder(any(),any(),anyDouble());
    }
    @Test void averagingPreOrderWaitSurvivesExecutionWrapperWithoutFabricatedFailure() {
        var ctx=new TestContext(pending("unused"));ctx.useSimulatorBroker();configureFiveAttemptPolicy(ctx);
        var trade=averagingTrade("CE",20.4);trade.setBrokerCredentialsId(55L);trade.setAppUserId(9L);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(averagingQuote(20.4,20.45,Instant.now())));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(ctx.service,"executeWithoutLock",trade,20.4,LocalDateTime.now(),false))
                .isInstanceOf(org.com.sharekhan.exception.EntryNotReadyException.class);
        verify(ctx.broker,never()).placeOrder(any(),any(),anyDouble());
        verify(ctx.triggeredRepo,never()).save(any());
    }
    @Test void averagingPriceRechecksFreshnessIdentitySpreadAndAllowsCheaperAsk() {
        var ctx=new TestContext(pending("unused"));var trade=averagingTrade("PE",20.4);
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(averagingQuote(20.2,20.3,Instant.now())));
        Object diagnostics=ReflectionTestUtils.invokeMethod(ctx.service,"analyseEntry",trade,20.4);
        for(var q:List.of(averagingQuote(20.2,20.3,Instant.now().minusSeconds(6)),
                averagingQuote(19,20.3,Instant.now()),QuoteCacheService.QuoteSnapshot.builder().bestBid(20.2).bestAsk(20.3).lastBookAt(Instant.now()).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:9").build())) {
            when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(q));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(ctx.service,"resolveEntryAttemptPrice",trade,diagnostics,0,20.4,false))
                    .isInstanceOf(org.com.sharekhan.exception.EntryNotReadyException.class);
        }
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(averagingQuote(20.2,20.3,Instant.now())));
        Double price=ReflectionTestUtils.invokeMethod(ctx.service,"resolveEntryAttemptPrice",trade,diagnostics,4,20.4,true);
        assertThat(price).isEqualTo(20.3);
    }

    private ScriptMasterEntity retryOption(TestContext ctx) {
        var option = ScriptMasterEntity.builder().scripCode(123456).tradingSymbol("NIFTY").exchange("NF")
                .instrumentType("OI").strikePrice(25000d).optionType("CE").expiry("30/06/2026").build();
        when(ctx.scriptRepo.findByScripCode(123456)).thenReturn(option);
        return option;
    }

    @Test
    void advancesStopsForLaterInitialTargetLegsWhenEarlierTargetFills() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity targetOne = new TriggeredTradeSetupEntity();
        targetOne.setId(1L); targetOne.setTargetOrderGroupId(99L); targetOne.setTargetStage(1);
        targetOne.setActualEntryPrice(120.0); targetOne.setTarget1(140.0);

        TriggeredTradeSetupEntity targetTwo = new TriggeredTradeSetupEntity();
        targetTwo.setId(2L); targetTwo.setTargetOrderGroupId(99L); targetTwo.setTargetStage(2);
        targetTwo.setStatus(TriggeredTradeStatus.EXECUTED);
        TriggeredTradeSetupEntity targetThree = new TriggeredTradeSetupEntity();
        targetThree.setId(3L); targetThree.setTargetOrderGroupId(99L); targetThree.setTargetStage(3);
        targetThree.setStatus(TriggeredTradeStatus.EXECUTED);
        when(ctx.triggeredRepo.findByTargetOrderGroupIdAndStatusIn(eq(99L), any()))
                .thenReturn(List.of(targetTwo, targetThree));

        ctx.service.advanceStagedTargetStops(targetOne);

        assertThat(targetTwo.getStopLoss()).isEqualTo(120.0);
        assertThat(targetThree.getStopLoss()).isEqualTo(120.0);
        verify(ctx.triggeredRepo).save(targetTwo);
        verify(ctx.triggeredRepo).save(targetThree);
    }

    @Test
    void repricedBritanniaTargetStillMovesRemainingStopsToCostAfterFill() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        TriggeredTradeSetupEntity first = new TriggeredTradeSetupEntity();
        first.setId(9290L);
        first.setSymbol("BRITANNIA");
        first.setAppUserId(9L);
        first.setBrokerCredentialsId(55L);
        first.setTargetOrderGroupId(9290L);
        first.setTargetStage(1);
        first.setActualEntryPrice(85.85);
        first.setTarget1(93.85);
        first.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        first.setExitReason("TARGET_ORDER_AUTO");
        first.setExitOrderId("205600634");
        when(ctx.triggeredRepo.findById(9290L)).thenReturn(Optional.of(first));
        TriggeredTradeSetupEntity second = new TriggeredTradeSetupEntity();
        second.setId(9295L);
        second.setTargetStage(2);
        second.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        second.setStopLoss(65.85);
        TriggeredTradeSetupEntity third = new TriggeredTradeSetupEntity();
        third.setId(9296L);
        third.setTargetStage(3);
        third.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        third.setStopLoss(65.85);
        when(ctx.triggeredRepo.findByTargetOrderGroupIdAndStatusIn(eq(9290L), any()))
                .thenReturn(List.of(first, second, third));

        assertThat(ctx.service.modifyExitOrderPrice(9290L, 93.0, "MANUAL_MODIFY").isSuccess()).isTrue();
        // A second edit must preserve the purpose too, without moving stops before a fill.
        assertThat(ctx.service.modifyExitOrderPrice(9290L, 93.0, "MANUAL_MODIFY").isSuccess()).isTrue();
        assertThat(first.getExitReason()).isEqualTo("TARGET_ORDER_AUTO");
        assertThat(second.getStopLoss()).isEqualTo(65.85);
        assertThat(third.getStopLoss()).isEqualTo(65.85);

        first.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        first.setExitPrice(93.0);
        ctx.service.advanceStagedTargetStopsAfterTargetExit(first);

        assertThat(second.getStopLoss()).isEqualTo(85.85);
        assertThat(third.getStopLoss()).isEqualTo(85.85);
        verify(ctx.triggeredRepo).save(second);
        verify(ctx.triggeredRepo).save(third);
    }

    @ParameterizedTest
    @CsvSource({
            "EXIT_ORDER_PLACED, STOP_LOSS_HIT, MANUAL_MODIFY",
            "TARGET_ORDER_PLACED, INTRADAY_CLOSE, MANUAL_MODIFY",
            "TARGET_ORDER_PLACED, TARGET_ORDER_AUTO, INTRADAY_CLOSE"
    })
    void nonTargetExitModificationsDoNotAdvanceStagedStops(
            TriggeredTradeStatus status, String existingReason, String requestedReason) {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setId(88L);
        trade.setBrokerCredentialsId(55L);
        trade.setTargetOrderGroupId(88L);
        trade.setTargetStage(1);
        trade.setActualEntryPrice(85.85);
        trade.setStatus(status);
        trade.setExitReason(existingReason);
        trade.setExitOrderId("EXIT-1");
        ctx.savedTrade.set(trade);

        assertThat(ctx.service.modifyExitOrderPrice(88L, 78.2, requestedReason).isSuccess()).isTrue();
        assertThat(trade.getExitReason()).isEqualTo(requestedReason);
        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        ctx.service.advanceStagedTargetStopsAfterTargetExit(trade);

        verify(ctx.triggeredRepo, never()).findByTargetOrderGroupIdAndStatusIn(any(), any());
    }

    @Test
    void doesNotAdvanceStagedStopsWhenALegExitsForStopLoss() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity stoppedLeg = new TriggeredTradeSetupEntity();
        stoppedLeg.setId(1L); stoppedLeg.setTargetOrderGroupId(99L); stoppedLeg.setTargetStage(1);
        stoppedLeg.setExitReason("STOP_LOSS_HIT");

        ctx.service.advanceStagedTargetStopsAfterTargetExit(stoppedLeg);

        verify(ctx.triggeredRepo, never()).findByTargetOrderGroupIdAndStatusIn(any(), any());
    }

    @Test
    void advancesFinalLegStopToFirstTargetWhenSecondTargetFills() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        TriggeredTradeSetupEntity firstTarget = new TriggeredTradeSetupEntity();
        firstTarget.setId(1L); firstTarget.setTargetStage(1); firstTarget.setTarget1(140.0);
        firstTarget.setExitReason("TARGET_HIT"); firstTarget.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        TriggeredTradeSetupEntity secondTarget = new TriggeredTradeSetupEntity();
        secondTarget.setId(2L); secondTarget.setTargetOrderGroupId(99L); secondTarget.setTargetStage(2);
        secondTarget.setTarget1(160.0); secondTarget.setExitReason("TARGET_HIT");
        TriggeredTradeSetupEntity finalLeg = new TriggeredTradeSetupEntity();
        finalLeg.setId(3L); finalLeg.setTargetStage(3); finalLeg.setStatus(TriggeredTradeStatus.EXECUTED);
        when(ctx.triggeredRepo.findByTargetOrderGroupIdAndStatusIn(eq(99L), any()))
                .thenReturn(List.of(firstTarget, finalLeg));

        ctx.service.advanceStagedTargetStops(secondTarget);

        assertThat(finalLeg.getStopLoss()).isEqualTo(140.0);
        verify(ctx.triggeredRepo).save(finalLeg);
    }

    @Test void strategyRunMembershipSurvivesBrokerPendingEntryAndClosingBlocksSubmission() {
        TestContext ctx=new TestContext(pending("run-order"));
        var coordinator=mock(org.com.sharekhan.strategy.run.StrategyRunCoordinator.class);
        ReflectionTestUtils.setField(ctx.service,"runCoordinator",coordinator);
        doAnswer(call->{((TriggerRequest)call.getArgument(0)).setStrategyRunId("run-am");return null;}).when(coordinator).attach(any());
        when(coordinator.isOpen("run-am")).thenReturn(true);
        var request=ctx.service.executeTrade(optionRequest());
        assertThat(request.getStrategyRunId()).isEqualTo("run-am");
        assertThat(ctx.savedTrade.get().getStrategyRunId()).isEqualTo("run-am");
        when(coordinator.isOpen("run-am")).thenReturn(false);
        assertThat(ctx.service.executeTradeFromEntity(request)).isNull();
        verify(ctx.broker,times(1)).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
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
        verify(ctx.broker).placeTriggerPriceEntryOrder(any(), any(BrokerContext.class), eq(123.45), eq(123.55));
        verify(ctx.eventPublisher).publishEvent(any(OrderPlacedEvent.class));
    }

    @Test
    void blocksRepeatedStockBazaariEntryForSameUserAndInstrumentOnSameDay() {
        TestContext ctx = new TestContext(pending("182038823"));
        TriggerRequest firstRequest = optionRequest();
        firstRequest.setSource("StockBazaari");
        when(ctx.triggerRepo.findDailySourceInstrumentRequests(eq("StockBazaari"), eq(123456), eq(9L), any(), any()))
                .thenReturn(List.of());

        TriggerTradeRequestEntity first = ctx.service.executeTrade(firstRequest);
        when(ctx.triggerRepo.findDailySourceInstrumentRequests(eq("StockBazaari"), eq(123456), eq(9L), any(), any()))
                .thenReturn(List.of(first));

        TriggerTradeRequestEntity repeated = ctx.service.executeTrade(firstRequest);

        assertThat(repeated).isSameAs(first);
        verify(ctx.broker, times(1)).placeTriggerPriceEntryOrder(any(), any(BrokerContext.class), anyDouble(), anyDouble());
    }

    @Test
    void brokerTriggerIsVisibleToRecoveryUntilBrokerCallReturns() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(false).status("Rejected").build());
        when(ctx.broker.placeTriggerPriceEntryOrder(any(), any(), anyDouble(), anyDouble()))
                .thenAnswer(invocation -> {
                    assertThat(ctx.service.isBrokerTriggerEntryInFlight(77L)).isTrue();
                    return OrderPlacementResult.builder().success(false).status("Rejected").build();
                });
        ctx.service.executeTrade(optionRequest());
        assertThat(ctx.service.isBrokerTriggerEntryInFlight(77L)).isFalse();
    }

    @Test
    void definitiveBrokerRejectionTerminatesRequestWithReasonAndNoFallback() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(false).status("Rejected")
                .definitiveBrokerRejection(true).rejectionReason("Dhan rejected order (HTTP 400)").build());
        when(ctx.triggerRepo.claimIfStatusEqualsWithOutcome(eq(77L), eq("ENTRY_SUBMITTING"), eq("REJECTED"),
                anyString(), anyString())).thenReturn(1);
        TradeAuditService audit = mock(TradeAuditService.class);
        ReflectionTestUtils.setField(ctx.service, "tradeAuditService", audit);

        TriggerTradeRequestEntity saved = ctx.service.executeTrade(optionRequest());

        assertThat(ctx.savedRequest.get().getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(saved.getReason()).isEqualTo("Dhan rejected order (HTTP 400)");
        assertThat(saved.getComment()).contains("automatic retry is disabled");
        verify(ctx.triggerRepo, never()).claimIfStatusEquals(77L, "ENTRY_SUBMITTING", "PLACED_PENDING_CONFIRMATION");
        verify(ctx.triggeredRepo, never()).save(any());
        verify(ctx.eventPublisher, never()).publishEvent(any());
        ArgumentCaptor<org.com.sharekhan.entity.TradeAuditEventEntity> event =
                ArgumentCaptor.forClass(org.com.sharekhan.entity.TradeAuditEventEntity.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getOutcome()).isEqualTo("REJECTED");
        assertThat(event.getValue().getTriggerRequestId()).isEqualTo(77L);
    }

    @Test
    void immediateEntryPreservesDefinitiveBrokerRejectionWithoutChasing() {
        TestContext ctx = new TestContext(pending("unused"));
        when(ctx.ltpCache.getLtp(123456)).thenReturn(123.45);
        when(ctx.broker.placeOrder(any(), any(), anyDouble())).thenReturn(OrderPlacementResult.builder()
                .success(false).status("Rejected").definitiveBrokerRejection(true)
                .rejectionReason("Dhan rejected order (HTTP 400)").build());

        TriggeredTradeSetupEntity rejected = ctx.service.executeTradeFromEntity(triggerRequestEntity(), true);

        assertThat(rejected.getStatus()).isEqualTo(TriggeredTradeStatus.REJECTED);
        assertThat(rejected.getReason()).isEqualTo("Dhan rejected order (HTTP 400)");
        verify(ctx.broker, times(1)).placeOrder(any(), any(), anyDouble());
        verify(ctx.broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
        verify(ctx.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void uncertainBrokerSideOptionEntryRetainsClaimAndBlocksRetry() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder()
                .success(false)
                .status("Rejected")
                .rejectionReason("RMS rejected trigger order")
                .attemptedPrice(123.45)
                .build());

        TriggerTradeRequestEntity saved = ctx.service.executeTrade(optionRequest());

        assertThat(saved.getStatus()).isEqualTo(TriggeredTradeStatus.ENTRY_SUBMITTING);
        assertThat(ctx.savedTrade.get()).isNull();
        verify(ctx.triggerRepo, never()).claimIfStatusEquals(
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
                .thenAnswer(call->{
                    ((TriggeredTradeSetupEntity)call.getArgument(0)).setBrokerExecutionProduct("CNC");
                    return OrderPlacementResult.builder()
                        .success(true)
                        .orderId("ENTRY-ORDER-1")
                        .status("Pending")
                        .attemptedPrice(125.0)
                        .build();});

        TriggeredTradeSetupEntity created = ctx.service.executeTradeFromEntity(triggerRequestEntity(), true);

        assertThat(created.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(created.getOrderId()).isEqualTo("ENTRY-ORDER-1");
        assertThat(created.getBrokerExecutionProduct()).isEqualTo("CNC");
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
        TriggerTradeRequestEntity request = triggerRequestEntity();
        request.setEntryPrice(10.50);
        when(ctx.broker.placeOrder(any(), any(BrokerContext.class), anyDouble()))
                .thenReturn(OrderPlacementResult.builder()
                        .success(true)
                        .orderId("SIM-ENTRY-1")
                        .status("Fully Executed")
                        .attemptedPrice(10.55)
                        .executedPrice(10.55)
                        .executedQuantity(50L)
                        .build());

        TriggeredTradeSetupEntity executed = ctx.service.executeTradeFromEntity(request);

        assertThat(executed.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(10.55));
    }

    @Test
    void simulatorDoesNotFillBuyAboveRequestBelowItsEntryPrice() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        ctx.useSimulatorBroker();
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);

        TriggeredTradeSetupEntity executed = ctx.service.executeTradeFromEntity(triggerRequestEntity());

        assertThat(executed).isNull();
        verify(ctx.broker, never()).placeOrder(any(), any(BrokerContext.class), anyDouble());
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
    void automaticEntryUsesFiveBoundedPriceLevelsThenPauses() {
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

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
        verify(ctx.broker).placeOrder(any(), any(BrokerContext.class), eq(10.55));
        ArgumentCaptor<Double> prices = ArgumentCaptor.forClass(Double.class);
        verify(ctx.broker, times(4)).modifyEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-5"), prices.capture());
        assertThat(prices.getAllValues()).containsExactly(10.60, 10.60, 10.60, 10.65);
        verify(ctx.broker, never()).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-5"));
        verify(ctx.telegramNotificationService).sendEntryActionMessage(eq(9L), anyString(), anyString());
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
    void partialEntryFillRemainsOpenWhenAttemptsAreExhausted() {
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

        assertThat(result.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(result.getQuantity()).isEqualTo(50L);
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
        verify(ctx.broker, never()).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL"));
        verify(ctx.telegramNotificationService, never()).sendTradeMessageForUser(
                eq(9L), eq("Order Executed ✅"), anyString());
    }

    @Test
    void partialEntryFillKeepsPollingWhileAwaitingUserAction() {
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
        verify(ctx.broker, never()).cancelEntryOrder(any(), any(BrokerContext.class), eq("ENTRY-PARTIAL-PENDING"));
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

    private TestContext pausedEntry() {
        TestContext ctx = new TestContext(pending("ACTION-1"));
        configureFiveAttemptPolicy(ctx);
        configureTightFreshQuote(ctx, 10.50, 10.60, 10.55);
        when(ctx.ltpCache.getLtp(123456)).thenReturn(10.55);
        when(ctx.broker.placeOrder(any(), any(), anyDouble())).thenReturn(pending("ACTION-1"));
        when(ctx.broker.modifyEntryOrder(any(), any(), anyString(), anyDouble())).thenReturn(pending("ACTION-1"));
        when(ctx.broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(orderHistory("Open"));
        ctx.service.executeTradeFromEntity(triggerRequestEntity());
        org.mockito.Mockito.clearInvocations(ctx.broker, ctx.telegramNotificationService);
        return ctx;
    }

    @Test
    void bestAskActionUsesExactAskBeyondCeilingAndRejectsDuplicateClick() {
        TestContext ctx = pausedEntry();
        configureTightFreshQuote(ctx, 149.9, 150.0, 149.95);
        String token = ctx.controls.get(88L).getDecisionToken();
        assertThat(ctx.service.handleEntryAction(88L, token, "market")).contains("150.00");
        assertThat(ctx.service.handleEntryAction(88L, token, "market")).contains("expired");
        verify(ctx.broker).modifyEntryOrder(any(), any(), eq("ACTION-1"), eq(150.0));
        verify(ctx.broker, never()).placeOrder(any(), any(), anyDouble());
        verify(ctx.broker, never()).cancelEntryOrder(any(), any(), anyString());
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
    }

    @Test
    void retryGetsFiveNewModificationsThenPausesEvenWhenQuoteDoesNotMove() throws Exception {
        TestContext ctx = pausedEntry();
        String token = ctx.controls.get(88L).getDecisionToken();
        assertThat(ctx.service.handleEntryAction(88L, token, "retry")).contains("Retry started");
        try {
            assertThat(ctx.controls.get(88L).getAttempts()).isZero();
            verify(ctx.broker, org.mockito.Mockito.timeout(7000).times(5))
                    .modifyEntryOrder(any(), any(), eq("ACTION-1"), anyDouble());
            // Wait for the fifth result to be persisted, not merely for the broker invocation.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (ctx.controls.get(88L).getState() != EntryChaseControl.State.PAUSED && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
            assertThat(ctx.controls.get(88L).getAttempts()).isEqualTo(5);
            assertThat(ctx.service.isEntryOrderChaseActive(88L)).isFalse();
            verify(ctx.broker, never()).cancelEntryOrder(any(), any(), anyString());
        } finally { ctx.service.stopEntryOrderChase(88L); }
    }

    @Test
    void staleQuoteDoesNotConsumeUserDecision() {
        TestContext ctx = pausedEntry();
        when(ctx.quoteCache.isBookStale(any(), any(Duration.class))).thenReturn(true);
        String token = ctx.controls.get(88L).getDecisionToken();
        assertThat(ctx.service.handleEntryAction(88L, token, "market")).contains("No fresh best ask");
        assertThat(ctx.controls.get(88L).getDecisionToken()).isEqualTo(token);
        verify(ctx.broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    @Test
    void brokerFillBeforeClickMakesActionObsolete() {
        TestContext ctx = pausedEntry();
        when(ctx.broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(orderHistory("Fully Executed"));
        assertThat(ctx.service.handleEntryAction(88L, ctx.controls.get(88L).getDecisionToken(), "cancel"))
                .contains("completed order");
        verify(ctx.broker, never()).cancelEntryOrder(any(), any(), anyString());
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.CLOSED);
    }

    @Test
    void partialFillMarketModificationPreservesOriginalTotalQuantity() {
        TestContext ctx = pausedEntry();
        when(ctx.broker.fetchOrderStatus(any(), any(), anyString())).thenReturn(partialOrderHistory("Partially Executed"));
        ctx.service.handleEntryAction(88L, ctx.controls.get(88L).getDecisionToken(), "market");
        ArgumentCaptor<TriggeredTradeSetupEntity> trade = ArgumentCaptor.forClass(TriggeredTradeSetupEntity.class);
        verify(ctx.broker).modifyEntryOrder(trade.capture(), any(), eq("ACTION-1"), eq(10.60));
        assertThat(trade.getValue().getQuantity()).isEqualTo(50L);
        assertThat(ctx.savedTrade.get().getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }

    @Test
    void cancellationWaitsForBrokerAndOffersFreshActionsIfStillOpen() {
        TestContext ctx = pausedEntry();
        String token = ctx.controls.get(88L).getDecisionToken();
        assertThat(ctx.service.handleEntryAction(88L, token, "cancel")).contains("Waiting for broker confirmation");
        assertThat(ctx.savedTrade.get().getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.CANCEL_PENDING);
        ctx.service.handleEntryAction(88L, token, "cancel");
        verify(ctx.broker).cancelEntryOrder(any(), any(), eq("ACTION-1"));
        ctx.service.recoverEntryDecisions();
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
        assertThat(ctx.controls.get(88L).getDecisionToken()).isNotEqualTo(token);
    }

    @Test
    void recoveryKeepsPausedStateAndRetriesFailedNotificationWithoutModifyingOrder() {
        TestContext ctx = pausedEntry();
        ctx.controls.get(88L).setTelegramMessageId(null);
        when(ctx.telegramNotificationService.sendEntryActionMessage(any(), anyString(), anyString()))
                .thenReturn(null, 101L);
        ctx.service.recoverEntryDecisions();
        assertThat(ctx.controls.get(88L).getTelegramMessageId()).isNull();
        ctx.service.recoverEntryDecisions();
        assertThat(ctx.controls.get(88L).getTelegramMessageId()).isEqualTo(101L);
        assertThat(ctx.service.isEntryOrderChaseActive(88L)).isFalse();
        verify(ctx.broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    @Test
    void recoveryDoesNotRepeatInterruptedBrokerAction() {
        TestContext ctx = pausedEntry();
        ctx.controls.get(88L).setState(EntryChaseControl.State.ACTION_PENDING);
        ctx.service.recoverEntryDecisions();
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.PAUSED);
        verify(ctx.broker, never()).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    @Test
    void restartResumesOnlyRemainingAttemptBudget() throws Exception {
        TestContext ctx = pausedEntry();
        EntryChaseControl control = ctx.controls.get(88L);
        control.setState(EntryChaseControl.State.RUNNING);
        control.setAttempts(4);
        ctx.service.recoverEntryDecisions();
        try {
            verify(ctx.broker, org.mockito.Mockito.timeout(3000)).modifyEntryOrder(any(), any(), anyString(), anyDouble());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (control.getState() != EntryChaseControl.State.PAUSED && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(control.getState()).isEqualTo(EntryChaseControl.State.PAUSED);
            assertThat(control.getAttempts()).isEqualTo(5);
            assertThat(ctx.service.isEntryOrderChaseActive(88L)).isFalse();
        } finally { ctx.service.stopEntryOrderChase(88L); }
    }

    @Test
    void brokerTriggeredChasePausesInsteadOfCancellingWhenBudgetIsReached() throws Exception {
        TestContext ctx = pausedEntry();
        EntryChaseControl control = ctx.controls.get(88L);
        control.setState(EntryChaseControl.State.RUNNING);
        control.setWaitForBrokerTrigger(true);
        control.setAttempts(4);
        ctx.service.recoverEntryDecisions();
        try {
            verify(ctx.broker, org.mockito.Mockito.timeout(3000)).modifyEntryOrder(any(), any(), eq("ACTION-1"), eq(10.65));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (control.getState() != EntryChaseControl.State.PAUSED && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(control.getState()).isEqualTo(EntryChaseControl.State.PAUSED);
            verify(ctx.broker, never()).cancelEntryOrder(any(), any(), anyString());
        } finally { ctx.service.stopEntryOrderChase(88L); }
    }

    @Test
    void concurrentMarketClicksSubmitOnlyOneModification() throws Exception {
        TestContext ctx = pausedEntry();
        String token = ctx.controls.get(88L).getDecisionToken();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return ctx.service.handleEntryAction(88L, token, "market"); });
            var b = pool.submit(() -> { start.await(); return ctx.service.handleEntryAction(88L, token, "market"); });
            start.countDown();
            assertThat(List.of(a.get(), b.get()).stream().filter(result -> result.contains("expired")).count()).isEqualTo(1L);
        } finally { pool.shutdownNow(); }
        verify(ctx.broker).modifyEntryOrder(any(), any(), anyString(), anyDouble());
    }

    @Test
    void timedStraddleCancellationKeepsPendingExposureAndDisablesUserRetry() {
        TestContext ctx = pausedEntry();
        var trade=ctx.savedTrade.get();trade.setSource("expiry-straddle:2026-10-13:9:55:NIFTY:AM");
        ctx.service.cancelTimedStrategyEntry(88L);
        verify(ctx.broker).cancelEntryOrder(eq(trade),any(),eq("ACTION-1"));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(trade.getQuantity()).isEqualTo(50L);
        assertThat(ctx.controls.get(88L).getState()).isEqualTo(EntryChaseControl.State.CANCEL_PENDING);
        ctx.controls.get(88L).setState(EntryChaseControl.State.PAUSED);
        assertThat(ctx.service.handleEntryAction(88L,ctx.controls.get(88L).getDecisionToken(),"retry")).contains("cannot be retried");
        verify(ctx.broker,never()).modifyEntryOrder(any(),any(),anyString(),anyDouble());
    }

    @Test
    void staleSavedStraddleCannotExecuteEvenWhenReplayedDirectly() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        var request=TriggerTradeRequestEntity.builder().source("expiry-straddle:2000-01-01:9:55:NIFTY:AM").build();
        assertThat(ctx.service.executeTradeFromEntity(request)).isNull();
        verify(ctx.broker,never()).placeOrder(any(),any(),anyDouble());
    }

    @Test
    void combinedProfitMarksRequireFreshUncrossedBooksAndLtpOnlyUpdatesCannotRefreshThem() {
        TestContext ctx = new TestContext(OrderPlacementResult.builder().success(true).build());
        var trade=TriggeredTradeSetupEntity.builder().scripCode(123456).actualEntryPrice(100d).optionType("CE").build();
        var now=Instant.parse("2026-10-13T04:30:00Z");
        for(var timestamp:List.of(now.minusSeconds(6),now.plusSeconds(1))) {
            when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder()
                    .bestBid(130d).bestAsk(131d).lastBookAt(timestamp).lastLtpAt(now).updatedAt(now).build()));
            assertThat(ctx.service.strategyProfitExitQuote(trade,now)).isEmpty();
        }
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder()
                .bestBid(130d).bestAsk(129d).lastBookAt(now).build()));
        assertThat(ctx.service.strategyProfitExitQuote(trade,now)).isEmpty();
        when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder()
                .bestBid(130d).bestAsk(131d).lastBookAt(now).build()));
        assertThat(ctx.service.strategyProfitExitQuote(trade,now)).isPresent();
    }

    private TestContext fundsQueue(java.util.OptionalDouble balance) {
        var ctx = new TestContext(pending("FUNDED-1"));
        when(ctx.broker.availableOptionBuyingFunds(any(), any())).thenReturn(balance);
        when(ctx.triggerRepo.claimIfStatusEqualsWithOutcome(eq(77L), eq("ENTRY_SUBMITTING"),
                eq("PLACED_PENDING_CONFIRMATION"), anyString(), anyString())).thenReturn(1);
        var request = triggerRequestEntity(); request.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        request.setExpiry("23/11/2026"); request.setCreatedAt(LocalDateTime.of(2026,10,28,10,0));
        ctx.savedRequest.set(request);
        var clock = java.time.Clock.fixed(java.time.ZonedDateTime.of(2026,10,28,12,0,0,0,
                java.time.ZoneId.of("Asia/Kolkata")).toInstant(),java.time.ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(ctx.service,"entryClock",clock);
        when(ctx.triggerRepo.findByStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION))
                .thenAnswer(i -> ctx.savedRequest.get().getStatus() == TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
                        ? List.of(ctx.savedRequest.get()) : List.of());
        when(ctx.ltpCache.getLtp(123456)).thenReturn(120d);
        when(ctx.ltpCache.getObservedAt(123456)).thenReturn(LocalDateTime.now(clock));
        return ctx;
    }

    @Test void insufficientFundsKeepsOnlyRequestAndDoesNotReachBroker() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(6000));
        ctx.service.retryFundsManagedOptionRequests();
        assertThat(ctx.savedRequest.get().getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(ctx.savedRequest.get().getReason()).isEqualTo("WAITING_FOR_BROKER_FUNDS");
        verify(ctx.broker,never()).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
        verify(ctx.triggeredRepo,never()).save(any());
    }

    @Test void missingBalanceFailsClosedAndAdminCannotBypassQueue() {
        var ctx = fundsQueue(java.util.OptionalDouble.empty());
        assertThat(ctx.service.executeTradeFromEntity(ctx.savedRequest.get())).isNull();
        assertThat(ctx.savedRequest.get().getReason()).isEqualTo("BROKER_FUNDS_UNAVAILABLE");
        verify(ctx.broker,never()).placeOrder(any(),any(),anyDouble());
        verify(ctx.broker,never()).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
    }

    @Test void fundedRequestUsesFullQuantityAndReserveBeforeCreatingPendingOrder() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(10000));
        ctx.service.retryFundsManagedOptionRequests();
        verify(ctx.broker).placeTriggerPriceEntryOrder(any(),any(),eq(123.45),anyDouble());
        assertThat(ctx.savedTrade.get().getQuantity()).isEqualTo(50);
        assertThat(ctx.savedTrade.get().getOrderId()).isEqualTo("FUNDED-1");
    }

    @Test void fundsForPremiumAloneDoNotCoverReserve() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(6200));
        ctx.service.retryFundsManagedOptionRequests();
        verify(ctx.broker,never()).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
    }

    @Test void confirmedExitWakesWaitingAccountAndStillRequiresFreshBalance() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(0));
        ctx.service.retryFundsManagedOptionRequests();
        when(ctx.broker.availableOptionBuyingFunds(any(),any())).thenReturn(java.util.OptionalDouble.of(10000));
        ctx.service.retryFundsManagedOptionRequests(); // Normal cooldown: no second balance call.
        verify(ctx.broker).availableOptionBuyingFunds(any(),any());
        ctx.service.brokerFundsMayBeReleased(TriggeredTradeSetupEntity.builder().brokerCredentialsId(99L).build());
        ctx.service.retryFundsManagedOptionRequests();
        verify(ctx.broker).availableOptionBuyingFunds(any(),any());
        ctx.service.brokerFundsMayBeReleased(TriggeredTradeSetupEntity.builder().brokerCredentialsId(55L).build());
        ctx.service.retryFundsManagedOptionRequests();
        verify(ctx.broker,times(2)).availableOptionBuyingFunds(any(),any());
        assertThat(ctx.savedTrade.get().getOrderId()).isEqualTo("FUNDED-1");
    }

    @Test void periodicRetryRechecksWithoutAnyExitAndSurvivesLostWakeup() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(0));
        ctx.service.retryFundsManagedOptionRequests();
        when(ctx.broker.availableOptionBuyingFunds(any(),any())).thenReturn(java.util.OptionalDouble.of(10000));
        var next = java.time.Clock.fixed(java.time.ZonedDateTime.of(2026,10,28,12,0,31,0,
                java.time.ZoneId.of("Asia/Kolkata")).toInstant(), java.time.ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(ctx.service,"entryClock",next);
        when(ctx.ltpCache.getObservedAt(123456)).thenReturn(LocalDateTime.now(next));
        ctx.service.retryFundsManagedOptionRequests();
        assertThat(ctx.savedTrade.get().getOrderId()).isEqualTo("FUNDED-1");
    }

    @Test void staleExpiredPriorDayAndTargetReachedRequestsNeverSubmit() {
        for (String scenario : List.of("stale", "expired", "priorDay", "target", "cutoff", "closedRun", "persisted")) {
            var ctx = fundsQueue(java.util.OptionalDouble.of(10000));
            switch (scenario) {
                case "stale" -> when(ctx.ltpCache.getObservedAt(123456)).thenReturn(LocalDateTime.of(2026,10,28,11,0));
                case "expired" -> ctx.savedRequest.get().setExpiry("27/10/2026");
                case "priorDay" -> {ctx.savedRequest.get().setIntraday(true);ctx.savedRequest.get().setCreatedAt(LocalDateTime.of(2026,10,27,10,0));}
                case "target" -> when(ctx.ltpCache.getLtp(123456)).thenReturn(150d);
                case "cutoff" -> ReflectionTestUtils.setField(ctx.service,"entryClock",java.time.Clock.fixed(
                        java.time.ZonedDateTime.of(2026,10,28,15,20,0,0,java.time.ZoneId.of("Asia/Kolkata")).toInstant(),java.time.ZoneId.of("Asia/Kolkata")));
                case "closedRun" -> {var runs=mock(org.com.sharekhan.strategy.run.StrategyRunCoordinator.class);
                    ReflectionTestUtils.setField(ctx.service,"runCoordinator",runs);ctx.savedRequest.get().setStrategyRunId("CLOSED");}
                case "persisted" -> when(ctx.triggeredRepo.findByTriggerRequestId(77L)).thenReturn(List.of(TriggeredTradeSetupEntity.builder().id(88L).build()));
            }
            ctx.service.retryFundsManagedOptionRequests();
            verify(ctx.broker,never()).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
        }
    }

    @Test void uncertainSubmissionNeverAutomaticallyRetries() {
        var ctx = fundsQueue(java.util.OptionalDouble.of(10000));
        when(ctx.broker.placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble())).thenThrow(new IllegalStateException("transport unavailable"));
        ctx.service.retryFundsManagedOptionRequests();
        ctx.service.brokerFundsMayBeReleased(TriggeredTradeSetupEntity.builder().brokerCredentialsId(55L).build());
        ctx.service.retryFundsManagedOptionRequests();
        assertThat(ctx.savedRequest.get().getStatus()).isEqualTo(TriggeredTradeStatus.ENTRY_SUBMITTING);
        verify(ctx.broker).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
    }

    @Test void concurrentWorkersCannotSpendTheSameUnreflectedBalance() throws Exception {
        var ctx=fundsQueue(java.util.OptionalDouble.of(10000));
        var first=ctx.savedRequest.get();
        var second=triggerRequestEntity();second.setId(78L);second.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        second.setExpiry("23/11/2026");second.setCreatedAt(first.getCreatedAt());
        when(ctx.triggerRepo.findById(78L)).thenReturn(Optional.of(second));
        when(ctx.triggerRepo.claimIfStatusEquals(78L,"PLACED_PENDING_CONFIRMATION","ENTRY_SUBMITTING")).thenAnswer(i->{second.setStatus(TriggeredTradeStatus.ENTRY_SUBMITTING);return 1;});
        when(ctx.triggerRepo.claimIfStatusEqualsWithOutcome(eq(78L),eq("ENTRY_SUBMITTING"),eq("PLACED_PENDING_CONFIRMATION"),anyString(),anyString())).thenReturn(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(()->{try {start.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                ReflectionTestUtils.invokeMethod(ctx.service,"tryPlaceBrokerSideEntryOrder",first);});
            var two=pool.submit(()->{try {start.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                ReflectionTestUtils.invokeMethod(ctx.service,"tryPlaceBrokerSideEntryOrder",second);});
            start.countDown();one.get(5,java.util.concurrent.TimeUnit.SECONDS);two.get(5,java.util.concurrent.TimeUnit.SECONDS);
            verify(ctx.broker,times(2)).availableOptionBuyingFunds(any(),any());
            verify(ctx.broker).placeTriggerPriceEntryOrder(any(),any(),anyDouble(),anyDouble());
            assertThat(List.of(first.getStatus(),second.getStatus())).containsExactlyInAnyOrder(
                    TriggeredTradeStatus.ENTRY_SUBMITTING,TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        } finally {pool.shutdownNow();}
    }

    @Test void spotLevelsAndSimulatorRemainOutsideLiveFundsQueue() {
        var ctx = fundsQueue(java.util.OptionalDouble.empty());
        ctx.savedRequest.get().setUseSpotForSl(true);
        assertThat(ctx.service.isFundsManagedOptionRequest(ctx.savedRequest.get())).isFalse();
        ctx.savedRequest.get().setUseSpotForSl(false);ctx.useSimulatorBroker();
        assertThat(ctx.service.isFundsManagedOptionRequest(ctx.savedRequest.get())).isFalse();
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
        private final EntryChaseControlRepository controlRepo = mock(EntryChaseControlRepository.class);
        private final Map<Long, EntryChaseControl> controls = new java.util.concurrent.ConcurrentHashMap<>();
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
                    .appUserId(9L)
                    .brokerName(Broker.SHAREKHAN.getDisplayName())
                    .customerId(999L)
                    .apiKey("api-key")
                    .clientCode("client-code")
                    .active(true)
                    .build()));
            when(brokerServiceFactory.getService(anyString())).thenReturn(broker);
            when(broker.availableOptionBuyingFunds(any(), any())).thenReturn(java.util.OptionalDouble.of(1000000d));
            when(broker.placeTriggerPriceEntryOrder(any(), any(BrokerContext.class), anyDouble(), anyDouble()))
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
            ReflectionTestUtils.setField(service, "entryChaseControlRepository", controlRepo);
            when(controlRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(controls.get(i.getArgument(0))));
            when(controlRepo.save(any())).thenAnswer(i -> {
                EntryChaseControl control = i.getArgument(0);
                controls.put(control.getTradeId(), control);
                return control;
            });
            when(controlRepo.findByStateNot(any())).thenAnswer(i -> controls.values().stream()
                    .filter(c -> c.getState() != i.getArgument(0)).toList());
            when(telegramNotificationService.sendEntryActionMessage(any(), anyString(), anyString())).thenReturn(100L);
        }

        private void useSimulatorBroker() {
            when(brokerCredentialsRepo.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                    .id(55L)
                    .appUserId(9L)
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

    @Test
    void freshExactSharekhanOptionFeedWorksForLiveAndSimulatorWithoutRestOrAuth() {
        for (boolean simulator : List.of(false, true)) {
            var ctx = new TestContext(pending("unused"));
            if (simulator) ctx.useSimulatorBroker();
            retryOption(ctx);
            var shoonya = mock(ShoonyaQuoteService.class);
            ReflectionTestUtils.setField(ctx.service, "shoonyaQuoteService", shoonya);
            when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(optionFeed(Instant.now(), "NF", "scrip:123456")));
            Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456, "executeTriggeredTrade");
            assertThat(price).isEqualTo(125.6);
            org.mockito.Mockito.verifyNoInteractions(shoonya, ctx.broker);
        }
    }

    @Test
    void sharekhanOptionFallbackRejectsStaleFutureMissingExchangeWrongScripAndMixedSource() {
        for (String fault : List.of("stale", "future", "exchange", "identity", "source", "scrip")) {
            var ctx = new TestContext(pending("unused")); retryOption(ctx);
            var builder = QuoteCacheService.QuoteSnapshot.builder().scripCode(fault.equals("scrip") ? 999 : 123456)
                    .ltpSource(fault.equals("source") ? "UNKNOWN" : "SHAREKHAN_WS")
                    .ltpExchange(fault.equals("exchange") ? "NC" : "NF")
                    .ltpProviderIdentity(fault.equals("identity") ? "scrip:999" : "scrip:123456")
                    .lastTradedPrice(125.6).updatedAt(Instant.now())
                    .lastLtpAt(fault.equals("stale") ? Instant.now().minusSeconds(60)
                            : fault.equals("future") ? Instant.now().plusSeconds(60) : Instant.now());
            when(ctx.quoteCache.getSnapshot(123456)).thenReturn(Optional.of(builder.build()));
            Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456, "executeTriggeredTrade");
            assertThat(price).as(fault).isNull();
            org.mockito.Mockito.verifyNoInteractions(ctx.broker);
        }
    }

    @Test
    void mstockExactOptionFallbackUsesReadOnlyClientAndRejectsWrongToken() {
        for (long returnedToken : List.of(123456L, 999L)) {
            var ctx = new TestContext(pending("unused")); var script = retryOption(ctx);
            var resolver = mock(MStockInstrumentResolver.class); var mstock = mock(MStockLtpService.class);
            ReflectionTestUtils.setField(ctx.service, "mStockInstrumentResolver", resolver);
            ReflectionTestUtils.setField(ctx.service, "mStockLtpService", mstock);
            when(resolver.resolveInstrumentKey(script)).thenReturn(Optional.of("NFO:NIFTY26JUN25000CE"));
            when(mstock.fetchLtpForInstrumentReadOnly("NFO:NIFTY26JUN25000CE"))
                    .thenReturn(Map.of("instrument_token", returnedToken, "last_price", 125.6));
            Double price = ReflectionTestUtils.invokeMethod(ctx.service, "resolveEntryReferencePrice", 123456, "executeTriggeredTrade");
            if (returnedToken == 123456L) assertThat(price).isEqualTo(125.6); else assertThat(price).isNull();
            verify(mstock, never()).fetchLtpForInstrument(anyString());
            org.mockito.Mockito.verifyNoInteractions(ctx.broker);
        }
    }

    private QuoteCacheService.QuoteSnapshot optionFeed(Instant received, String exchange, String identity) {
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(123456).ltpSource("SHAREKHAN_WS")
                .ltpExchange(exchange).ltpProviderIdentity(identity).lastTradedPrice(125.6)
                .lastLtpAt(received).updatedAt(Instant.now()).build();
    }

}
