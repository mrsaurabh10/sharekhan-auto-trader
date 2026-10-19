package org.com.sharekhan.strategy.straddle;

import org.junit.jupiter.api.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.cache.LtpCacheService;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ExpiryLongStraddleStrategyTest {
    final LocalDate day=LocalDate.of(2026,10,13);
    final StrategySupport support=mock(StrategySupport.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final ExpiryStraddleSessionRepository sessions=mock(ExpiryStraddleSessionRepository.class);
    final ExpiryStraddleSessionStore store=mock(ExpiryStraddleSessionStore.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final LtpCacheService prices=mock(LtpCacheService.class);
    final NseMarketCalendar calendar=mock(NseMarketCalendar.class);
    ExpiryLongStraddleStrategy strategy(LocalTime time) {
        return new ExpiryLongStraddleStrategy(support,scripts,sessions,store,trades,execution,prices,calendar,
                Clock.fixed(day.atTime(time).atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
    }
    StrategyApplyRequest request() {
        var r=new StrategyApplyRequest();r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setLots(2);return r;
    }
    ExpiryStraddleSession session() {
        return ExpiryStraddleSession.builder().id("2026-10-13:1:2:NIFTY:AM").appUserId(1L).brokerCredentialsId(2L)
                .tradingDay(day).symbol("NIFTY").slot("AM").strike(25000).lots(2).expectedQuantity(130)
                .claimedAt(day.atTime(9,30)).exitAt(day.atTime(10,30)).build();
    }
    TriggeredTradeSetupEntity leg(long id,String side,TriggeredTradeStatus state) {
        return TriggeredTradeSetupEntity.builder().id(id).optionType(side).quantity(130L).status(state).build();
    }
    @BeforeEach void setup() {
        when(calendar.isTradingDay(day)).thenReturn(true);
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(c->StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
        when(scripts.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(eq("NIFTY"),anyString(),eq("13/10/2026"))).thenReturn(List.of(25000d,25050d));
        when(support.resolveSpotScript("NIFTY")).thenReturn(ScriptMasterEntity.builder().scripCode(20000).build());
        when(prices.getLtp(20000)).thenReturn(25020d);when(prices.getObservedAt(20000)).thenReturn(day.atTime(9,30));
        for(String side:List.of("CE","PE")) when(scripts.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry("NIFTY",25000d,side,"13/10/2026"))
                .thenReturn(Optional.of(ScriptMasterEntity.builder().scripCode(side.equals("CE")?1:2).lotSize(65).exchange("NF").build()));
        when(execution.strategyEntryReferencePrice(anyInt())).thenReturn(100d);
        when(store.claim(any())).thenReturn(true);
        when(execution.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(5L).status(TriggeredTradeStatus.EXECUTED).build());
    }
    @Test void buysSameExpirySameStrikeBothLegsWithoutStopsOrTargets() {
        assertThat(strategy(LocalTime.of(9,30)).apply(request()).getStatus()).isEqualTo("triggered");
        var captor=org.mockito.ArgumentCaptor.forClass(TriggerRequest.class);verify(execution,times(2)).executeTriggeredTrade(captor.capture());
        assertThat(captor.getAllValues()).extracting(TriggerRequest::getOptionType).containsExactly("CE","PE");
        for(var r:captor.getAllValues()) {
            assertThat(r.getInstrument()).isEqualTo("NIFTY");assertThat(r.getStrikePrice()).isEqualTo(25000);
            assertThat(r.getExpiry()).isEqualTo("13/10/2026");assertThat(r.getQuantity()).isEqualTo(2);
            assertThat(r.getIntraday()).isTrue();assertThat(r.getTslEnabled()).isFalse();
            assertThat(r.getStopLoss()).isNull();assertThat(r.getTarget1()).isNull();
            assertThat(r.getSource()).isEqualTo("expiry-straddle:2026-10-13:1:2:NIFTY:AM");
        }
        verify(store).claim(argThat(s->s.getExpectedQuantity()==130 && s.getExitAt().equals(day.atTime(10,30))));
    }
    @Test void afternoonSelectsFreshAtmAndUsesIndependentClaim() {
        when(prices.getObservedAt(20000)).thenReturn(day.atTime(13,30));
        strategy(LocalTime.of(13,30)).apply(request());
        verify(store).claim(argThat(s->s.getId().endsWith(":PM") && s.getExitAt().equals(day.atTime(14,30))));
    }
    @Test void noEntryOutsideWindowOrOnHolidayOrWithoutExpiryContracts() {
        strategy(LocalTime.of(9,31)).apply(request());verifyNoInteractions(execution,store);
        when(calendar.isTradingDay(day)).thenReturn(false);strategy(LocalTime.of(9,30)).apply(request());verifyNoInteractions(execution,store);
        when(calendar.isTradingDay(day)).thenReturn(true);
        when(scripts.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry("NIFTY","PE","13/10/2026")).thenReturn(List.of());
        strategy(LocalTime.of(9,30)).apply(request());verifyNoInteractions(execution,store);
    }
    @Test void staleSpotAndMissingPremiumNeverClaimOrSubmitPair() {
        when(prices.getObservedAt(20000)).thenReturn(day.atTime(9,29));strategy(LocalTime.of(9,30)).apply(request());verifyNoInteractions(store);
        when(prices.getObservedAt(20000)).thenReturn(day.atTime(9,30));when(execution.strategyEntryReferencePrice(2)).thenReturn(null);
        strategy(LocalTime.of(9,30)).apply(request());verify(execution,never()).executeTriggeredTrade(any());verifyNoInteractions(store);
    }
    @Test void persistedClaimOrLosingConcurrentClaimPreventsReplay() {
        when(sessions.existsById(anyString())).thenReturn(true);strategy(LocalTime.of(9,30)).apply(request());verifyNoInteractions(execution,store);
        when(sessions.existsById(anyString())).thenReturn(false);when(store.claim(any())).thenReturn(false);
        strategy(LocalTime.of(9,30)).apply(request());verify(execution,never()).executeTriggeredTrade(any());
    }
    @Test void existingUnresolvedMorningExposureBlocksAfternoon() {
        when(sessions.findByTradingDayLessThanEqual(day)).thenReturn(List.of(session()));
        when(trades.findBySource(anyString())).thenReturn(List.of(leg(1,"CE",TriggeredTradeStatus.EXIT_ORDER_PLACED)));
        strategy(LocalTime.of(13,30)).apply(request());verifyNoInteractions(execution,store);
    }
    @Test void failureOfSecondLegRequestsAbortAndUnwindsFirst() {
        when(execution.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().status(TriggeredTradeStatus.EXECUTED).build()).thenThrow(new IllegalStateException("reject"));
        when(trades.findBySource(anyString())).thenReturn(List.of(leg(1,"CE",TriggeredTradeStatus.EXECUTED)));
        assertThat(strategy(LocalTime.of(9,30)).apply(request()).getStatus()).isEqualTo("error");
        verify(sessions).recordOutcome(anyString(),eq(true),anyString());verify(execution).squareOffTimedStrategyTrade(1L);
    }
    @Test void balancedPairHoldsUntilExitAndBothLegsCloseAtExactBoundary() {
        var s=session();when(trades.findBySource(ExpiryLongStraddleStrategy.source(s))).thenReturn(List.of(leg(1,"CE",TriggeredTradeStatus.EXECUTED),leg(2,"PE",TriggeredTradeStatus.EXECUTED)));
        strategy(LocalTime.NOON).manageSession(s,day.atTime(10,29,59));verifyNoInteractions(execution);
        strategy(LocalTime.NOON).manageSession(s,day.atTime(10,30));verify(execution).squareOffTimedStrategyTrade(1L);verify(execution).squareOffTimedStrategyTrade(2L);
    }
    @Test void partialOrUnconfirmedPairUnwindsAfterEntryMinuteWithoutInventingFill() {
        var s=session();var partial=leg(2,"PE",TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        when(trades.findBySource(anyString())).thenReturn(List.of(leg(1,"CE",TriggeredTradeStatus.EXECUTED),partial));
        strategy(LocalTime.NOON).manageSession(s,day.atTime(9,31));
        assertThat(s.isAbortRequested()).isTrue();verify(execution).squareOffTimedStrategyTrade(1L);verify(execution).cancelTimedStrategyEntry(2L);
        assertThat(partial.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }
    @Test void restartExitScanIsIndependentOfSubscriptionsAndRetriesLateFills() {
        var s=session();when(sessions.findByTradingDayLessThanEqual(day)).thenReturn(List.of(s));
        when(trades.findBySource(anyString())).thenReturn(List.of(leg(1,"CE",TriggeredTradeStatus.EXITED_SUCCESS),leg(2,"PE",TriggeredTradeStatus.EXECUTED)));
        strategy(LocalTime.of(14,31)).manageExits();verify(execution).squareOffTimedStrategyTrade(2L);verify(execution,never()).squareOffTimedStrategyTrade(1L);
    }
    org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot quote(double bid,Instant time) {
        return org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot.builder().bestBid(bid).bestAsk(bid+0.05).lastBookAt(time).build();
    }
    List<TriggeredTradeSetupEntity> filledPair(ExpiryStraddleSession s) {
        var ce=leg(1,"CE",TriggeredTradeStatus.EXECUTED);var pe=leg(2,"PE",TriggeredTradeStatus.EXECUTED);
        for(var trade:List.of(ce,pe)) {trade.setActualEntryPrice(100d);trade.setEntryPrice(20d);trade.setQuantity(s.getExpectedQuantity());}
        when(trades.findBySource(anyString())).thenReturn(List.of(ce,pe));
        return List.of(ce,pe);
    }
    @Test void capturesConfiguredProfitTargetForEachNewSession() {
        var request=request();request.setProfitTargetPerLot(new java.math.BigDecimal("2000"));
        strategy(LocalTime.of(9,30)).apply(request);
        verify(store).claim(argThat(s->s.getProfitTargetPerLot().compareTo(new java.math.BigDecimal("2000"))==0 && s.getLots()==2));
    }
    @Test void usesCombinedActualFillProfitAndScalesRupeesByLots() {
        var s=session();s.setProfitTargetPerLot(new java.math.BigDecimal("2000"));var pair=filledPair(s);
        var now=day.atTime(10,0);var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        // CE alone gains 5200, but PE loses 1300: combined3900 is below the two-lot target4000.
        when(execution.strategyProfitExitQuote(pair.get(0),instant)).thenReturn(Optional.of(quote(140,instant)));
        when(execution.strategyProfitExitQuote(pair.get(1),instant)).thenReturn(Optional.of(quote(90,instant)));
        strategy(LocalTime.of(10,0)).manageSession(s,now);
        verify(execution,never()).squareOffProfitStrategyTrade(anyLong());verify(sessions,never()).claimProfitExit(anyString(),anyString());
        when(execution.strategyProfitExitQuote(pair.get(0),instant)).thenReturn(Optional.of(quote(141,instant)));
        when(sessions.claimProfitExit(anyString(),anyString())).thenReturn(1);
        strategy(LocalTime.of(10,0)).manageSession(s,now);
        assertThat(s.isProfitExitRequested()).isTrue();
        var order=inOrder(sessions,execution);order.verify(sessions).claimProfitExit(eq(s.getId()),contains("4000"));
        order.verify(execution).squareOffProfitStrategyTrade(1L);order.verify(execution).squareOffProfitStrategyTrade(2L);
        verify(execution,never()).squareOffTimedStrategyTrade(anyLong());
    }
    @Test void targetTriggersAtEqualityAndOneLotUsesOneLotThreshold() {
        var s=session();s.setLots(1);s.setExpectedQuantity(65);s.setProfitTargetPerLot(new java.math.BigDecimal("1950"));var pair=filledPair(s);
        var now=day.atTime(10,0);var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        for(var trade:pair) when(execution.strategyProfitExitQuote(trade,instant)).thenReturn(Optional.of(quote(115,instant)));
        when(sessions.claimProfitExit(anyString(),anyString())).thenReturn(1);
        strategy(LocalTime.of(10,0)).manageSession(s,now);
        verify(sessions).claimProfitExit(eq(s.getId()),contains("1950"));verify(execution).squareOffProfitStrategyTrade(1L);
    }
    @Test void missingBookOrFillOrUnsynchronisedBooksNeverInventProfit() {
        var s=session();s.setProfitTargetPerLot(java.math.BigDecimal.ONE);var pair=filledPair(s);
        var now=day.atTime(10,0);var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(execution.strategyProfitExitQuote(pair.get(0),instant)).thenReturn(Optional.of(quote(150,instant)));
        strategy(LocalTime.of(10,0)).manageSession(s,now);
        when(execution.strategyProfitExitQuote(pair.get(1),instant)).thenReturn(Optional.of(quote(150,instant.minusSeconds(3))));
        strategy(LocalTime.of(10,0)).manageSession(s,now);
        when(execution.strategyProfitExitQuote(pair.get(1),instant)).thenReturn(Optional.of(quote(150,instant)));
        pair.get(0).setActualEntryPrice(null);strategy(LocalTime.of(10,0)).manageSession(s,now);
        verify(sessions,never()).claimProfitExit(anyString(),anyString());verify(execution,never()).squareOffProfitStrategyTrade(anyLong());
    }
    @Test void persistentProfitExitResumesAfterRestartWithoutRecheckingThresholdAndSurvivesOneSellFailure() {
        var s=session();s.setProfitTargetPerLot(new java.math.BigDecimal("2000"));s.setProfitExitRequested(true);
        when(sessions.findByProfitTargetPerLotIsNotNullAndTradingDayLessThanEqual(day)).thenReturn(List.of(s));
        var pair=filledPair(s);pair.get(0).setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        doThrow(new IllegalStateException("exit failed")).when(execution).squareOffProfitStrategyTrade(1L);
        strategy(LocalTime.of(10,0)).monitorProfitTargets();
        verify(execution).squareOffProfitStrategyTrade(2L);verify(execution,never()).strategyProfitExitQuote(any(),any());
        assertThat(s.isProfitExitRequested()).isTrue();assertThat(s.isAbortRequested()).isFalse();
    }
    @Test void noProfitTargetMeansNoPriceLookupsAndOriginalTimedExitStillRuns() {
        var s=session();filledPair(s);
        strategy(LocalTime.of(10,0)).manageSession(s,day.atTime(10,0));verifyNoInteractions(execution);
        strategy(LocalTime.of(10,30)).manageSession(s,day.atTime(10,30));verify(execution).squareOffTimedStrategyTrade(1L);
    }
    @Test void missingProfitQuotesDoNotBlockScheduledExit() {
        var s=session();s.setProfitTargetPerLot(new java.math.BigDecimal("2000"));filledPair(s);
        strategy(LocalTime.of(10,30)).manageSession(s,day.atTime(10,30));
        verify(execution).squareOffTimedStrategyTrade(1L);verify(execution).squareOffTimedStrategyTrade(2L);
    }
}
