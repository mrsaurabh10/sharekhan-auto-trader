package org.com.sharekhan.strategy.nifty;

import jakarta.persistence.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NiftyThreeCandlePaperBrokerTest {
    final LocalDateTime now=LocalDateTime.of(2026,11,9,10,1);
    final Instant instant=now.atZone(NiftyThreeCandleRules.IST).toInstant();
    final EntityManager em=mock(EntityManager.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final StrategyRunCoordinator runs=mock(StrategyRunCoordinator.class);
    final TradeAuditService audit=mock(TradeAuditService.class);
    final NiftyThreeCandlePaperBroker paper=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,Clock.fixed(instant,NiftyThreeCandleRules.IST));
    final StrategyApplyRequest r=new StrategyApplyRequest();
    final ScriptMasterEntity option=NiftyThreeCandleStrategyTest.option(123,"CE","10/11/2026",23000);
    final NiftyThreeCandleRules.Signal signal=new NiftyThreeCandleRules.Signal("CE",now.withMinute(0),23001,22980,23000,22999);
    final List<TriggeredTradeSetupEntity> rows=new ArrayList<>();
    @BeforeEach void setup(){
        r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setLots(1);
        var broker=BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build();
        when(em.find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(broker);
        when(em.find(BrokerCredentialsEntity.class,2L)).thenReturn(broker);
        when(trades.findBySource(NiftyThreeCandleRules.SOURCE)).thenAnswer(c->List.copyOf(rows));
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23002)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,instant)));
        doAnswer(c->{((TriggerRequest)c.getArgument(0)).setStrategyRunId("run-1");return null;}).when(runs).attach(any());
        when(em.find(StrategyRun.class,"run-1",LockModeType.PESSIMISTIC_WRITE)).thenReturn(StrategyRun.builder().id("run-1").appUserId(1L).brokerCredentialsId(2L).status("OPEN").build());
        when(requests.save(any())).thenAnswer(c->{TriggerTradeRequestEntity t=c.getArgument(0);t.setId(11L);return t;});
        when(trades.saveAndFlush(any())).thenAnswer(c->{TriggeredTradeSetupEntity t=c.getArgument(0);if(t.getId()==null)t.setId(22L);
            new TradeCostPersistenceListener().calculateNetPnl(t);return t;});
    }
    QuoteCacheService.QuoteSnapshot spot(double p){return QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(p).lastLtpAt(instant)
            .ltpSource("SHAREKHAN_WS").ltpExchange("NC").ltpProviderIdentity("scrip:20000").build();}
    QuoteCacheService.QuoteSnapshot book(double bid,double ask,double bq,double aq,Instant at){return QuoteCacheService.QuoteSnapshot.builder().scripCode(123)
            .bestBid(bid).bestAsk(ask).bidQuantity(bq).askQuantity(aq).coherentBook(true).lastBookAt(at)
            .bookSource("SHAREKHAN_WS").bookExchange("NF").bookProviderIdentity("scrip:123").build();}
    TriggerTradeRequestEntity open(){return paper.open(r,signal,option,20000,List.of(option));}
    TriggeredTradeSetupEntity position(){
        var t=TriggeredTradeSetupEntity.builder().id(22L).appUserId(1L).brokerCredentialsId(2L).strategyRunId("run-1")
                .source(NiftyThreeCandleRules.SOURCE).scripCode(123).spotScripCode(20000).symbol("NIFTY").optionType("CE").exchange("NF")
                .actualEntryPrice(101d).entryPrice(23002d).stopLoss(22980d).target1(23024d).quantity(65L).lots(1).intraday(true)
                .status(TriggeredTradeStatus.EXECUTED).entryAt(now.minusMinutes(1)).build();
        when(em.find(TriggeredTradeSetupEntity.class,22L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(t);rows.add(t);return t;
    }
    @Test void entryAcceptsBookReceivedDuringSnapshotRead() {
        var moving=new NiftyThreeCandleTimingTest.MovingClock(instant);
        var timed=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,moving);
        when(quotes.getSnapshot(123)).thenAnswer(c->{moving.at=instant.plusMillis(1);return Optional.of(book(100,101,65,65,moving.at));});
        assertEquals(11L,timed.open(r,signal,option,20000,List.of(option)).getId());
    }
    @Test void exitAcceptsBookReceivedDuringSnapshotRead() {
        var t=position();t.setExitReason("NIFTY_3C_STOP");
        var moving=new NiftyThreeCandleTimingTest.MovingClock(instant);
        var timed=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,moving);
        when(quotes.getSnapshot(123)).thenAnswer(c->{moving.at=instant.plusMillis(1);return Optional.of(book(100,101,65,65,moving.at));});
        timed.evaluate(22L);
        assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
        assertEquals(100,t.getExitPrice());
    }
    @Test void entryCannotUseWindowThatExpiresDuringPriorTradeLookup() {
        var moving=new NiftyThreeCandleTimingTest.MovingClock(instant);
        var timed=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,moving);
        when(trades.findBySource(NiftyThreeCandleRules.SOURCE)).thenAnswer(c->{moving.at=instant.plusSeconds(240);return List.of();});
        var error=assertThrows(IllegalStateException.class,()->timed.open(r,signal,option,20000,List.of(option)));
        assertTrue(error.getMessage().contains("window has expired"));
        verify(requests,never()).save(any());
    }
    @Test void entryRechecksDeadlineAndFreshnessAfterRunLocks() {
        var moving=new NiftyThreeCandleTimingTest.MovingClock(instant);
        var timed=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,moving);
        doAnswer(c->{((TriggerRequest)c.getArgument(0)).setStrategyRunId("run-1");moving.at=instant.plusSeconds(6);return null;}).when(runs).attach(any());
        assertTrue(assertThrows(IllegalStateException.class,()->timed.open(r,signal,option,20000,List.of(option))).getMessage().contains("quotes expired"));
        moving.at=instant;
        doAnswer(c->{((TriggerRequest)c.getArgument(0)).setStrategyRunId("run-1");moving.at=instant.plusSeconds(240);return null;}).when(runs).attach(any());
        assertTrue(assertThrows(IllegalStateException.class,()->timed.open(r,signal,option,20000,List.of(option))).getMessage().contains("window has expired"));
        verify(requests,never()).save(any());
    }
    @Test void entryBuysAskAndRecordsSpotBracketSeparatelyWithLotMetadata(){
        var req=open();assertEquals(65L,req.getQuantity());assertEquals(23002,req.getEntryPrice());assertEquals(23024,req.getTarget1());
        var capture=org.mockito.ArgumentCaptor.forClass(TriggeredTradeSetupEntity.class);verify(trades).saveAndFlush(capture.capture());
        var t=capture.getValue();assertEquals(101,t.getActualEntryPrice());assertEquals(22980,t.getStopLoss());assertFalse(t.getTslEnabled());
        assertEquals("BUY",t.getEntryAction());assertEquals("CE",t.getOptionType());assertTrue(t.getOrderId().startsWith("SIM-N3C-"));
        verify(em).find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE);verify(runs).attach(any());
    }
    @Test void mirroredPeIsStillBoughtAndUsesDownsideSpotTarget(){
        var pe=NiftyThreeCandleStrategyTest.option(123,"PE","10/11/2026",23000);
        var s=new NiftyThreeCandleRules.Signal("PE",signal.end(),23003,23020,23000,23001);
        var req=paper.open(r,s,pe,20000,List.of(pe));assertEquals("PE",req.getOptionType());assertEquals("BUY",req.getEntryAction());
        assertEquals(22984,req.getTarget1());assertEquals(23020,req.getStopLoss());
    }
    @Test void expiredSignalAndStaleOrShallowBooksNeverCreateRequests(){
        var expired=new NiftyThreeCandleRules.Signal("CE",signal.end().minusMinutes(5),23001,22980,23000,22999);
        assertThrows(IllegalStateException.class,()->paper.open(r,expired,option,20000,List.of(option)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,instant.minusSeconds(6))));
        assertThrows(IllegalStateException.class,this::open);
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,64,instant)));
        assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void duplicateAndOppositeOpenPositionBlockBothSides(){
        var t=position();t.setOptionType("PE");assertThrows(IllegalStateException.class,this::open);
        t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);t.setExitPrice(100d);t.setExitedAt(now.minusSeconds(30));
        t.setEntryAt(now.minusMinutes(5));t.setComment("signal="+signal.key()+"; filled");
        assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void patternFormedWhilePreviousPositionWasOpenIsNotTradedAfterItsExit(){
        var t=position();t.setEntryAt(now.minusMinutes(5));t.setExitedAt(now.minusSeconds(20));t.setExitPrice(102d);t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void changedAtmContractWaitsForTheNewContractBook(){
        var closer=NiftyThreeCandleStrategyTest.option(124,"CE","10/11/2026",23002);
        assertThrows(IllegalStateException.class,()->paper.open(r,signal,option,20000,List.of(option,closer)));verify(requests,never()).save(any());
    }
    @Test void liveAccountCannotUsePaperEntryEvenByCallingTheBrokerDirectly(){
        when(em.find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build());
        assertThrows(IllegalArgumentException.class,this::open);verifyNoInteractions(requests);
    }
    @Test void cancelledSubscriptionAndClosingRunCannotRaceIntoAnEntry(){
        r.setSubscriptionId(8L);
        when(em.find(StrategySubscriptionEntity.class,8L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(StrategySubscriptionEntity.builder()
                .appUserId(1L).brokerCredentialsId(2L).templateId(NiftyThreeCandleRules.TEMPLATE).status("PAUSED").build());
        assertThrows(IllegalStateException.class,this::open);
        r.setSubscriptionId(null);
        when(em.find(StrategyRun.class,"run-1",LockModeType.PESSIMISTIC_WRITE)).thenReturn(StrategyRun.builder().id("run-1").appUserId(1L).brokerCredentialsId(2L).status("CLOSING").build());
        assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void stopUsesCurrentSpotTickAndSellsBidWithoutWaitingForCandleClose(){
        var t=position();when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22979)));
        paper.evaluate(22L);assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals(100,t.getExitPrice());
        assertEquals("NIFTY_3C_STOP",t.getExitReason());assertEquals(-65,t.getPnl());assertTrue(t.getEffectivePnl()<t.getPnl());
    }
    @Test void peStopIsUpwardAndPeTargetIsDownward(){
        var t=position();t.setOptionType("PE");t.setEntryPrice(23002d);t.setStopLoss(23020d);t.setTarget1(22984d);
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22983)));paper.evaluate(22L);
        assertEquals("NIFTY_3C_TARGET",t.getExitReason());assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
    }
    @Test void observedSpotStopPersistsWhileBookIsStaleAndCannotDisappearOnRecovery(){
        var t=position();when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22979)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,instant.minusSeconds(6))));paper.evaluate(22L);
        assertEquals(TriggeredTradeStatus.EXECUTED,t.getStatus());assertEquals("NIFTY_3C_STOP",t.getExitReason());
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(99,100,65,65,instant)));when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23010)));
        paper.evaluate(22L);assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals("NIFTY_3C_STOP",t.getExitReason());assertEquals(99,t.getExitPrice());
    }
    @Test void dailyLossIncludesNetMarksClosesTheWholeLotAndLatchesForTheDay(){
        var t=position();t.setActualEntryPrice(200d);when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(120,121,65,65,instant)));
        paper.evaluate(22L);assertEquals("NIFTY_3C_DAILY_LOSS",t.getExitReason());assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
        verify(runs).requestClose("run-1","NIFTY_3C_DAILY_LOSS");
        // Even a subsequent favorable re-mark cannot re-enable the daily latch.
        t.setExitPrice(201d);assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void dailyLossFromTheOtherSideCountsAndYesterdayResets(){
        var t=position();t.setEntryAt(now.minusMinutes(10));t.setExitedAt(now.minusMinutes(5));t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        t.setOptionType("PE");t.setActualEntryPrice(200d);t.setExitPrice(120d);
        assertThrows(IllegalStateException.class,this::open);
        t.setEntryAt(now.minusDays(1));t.setExitedAt(now.minusDays(1).plusMinutes(5));assertDoesNotThrow(this::open);
    }
    @Test void unavailableDepthNeverCreatesAFakeExitAndManualCloseUsesBid(){
        var t=position();when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,64,65,instant)));
        paper.close(22L,"STRATEGY_MANUAL_CLOSE");assertEquals(TriggeredTradeStatus.EXECUTED,t.getStatus());
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(99,101,65,65,instant)));paper.close(22L,"STRATEGY_MANUAL_CLOSE");
        assertEquals(99,t.getExitPrice());assertEquals("STRATEGY_MANUAL_CLOSE",t.getExitReason());
    }
    @Test void manualCloseTriggerRemainsPendingWhenBookIsStale(){
        var t=position();when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,instant.minusSeconds(6))));
        paper.close(22L,"STRATEGY_MANUAL_CLOSE");assertEquals(TriggeredTradeStatus.EXECUTED,t.getStatus());assertEquals("STRATEGY_MANUAL_CLOSE",t.getExitReason());
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(98,101,65,65,instant)));paper.evaluate(22L);
        assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals(98,t.getExitPrice());
    }
    @Test void sessionCloseDoesNotRequireASpotQuoteAndStillUsesOptionBid(){
        var t=position();when(quotes.getSnapshot(20000)).thenReturn(Optional.empty());
        var late=new NiftyThreeCandlePaperBroker(em,trades,requests,quotes,runs,audit,Clock.fixed(now.withHour(15).withMinute(30).atZone(NiftyThreeCandleRules.IST).toInstant(),NiftyThreeCandleRules.IST));
        var at=now.withHour(15).withMinute(30).atZone(NiftyThreeCandleRules.IST).toInstant();
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,at)));late.evaluate(22L);
        assertEquals("NIFTY_3C_SESSION_CLOSE",t.getExitReason());assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
    }
    @Test void exitTodayFromAnOverduePositionStillCountsAgainstTodaysBudget(){
        var t=position();t.setEntryAt(now.minusDays(1));t.setExitedAt(now.minusMinutes(5));t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        t.setActualEntryPrice(200d);t.setExitPrice(120d);assertThrows(IllegalStateException.class,this::open);
    }
}
