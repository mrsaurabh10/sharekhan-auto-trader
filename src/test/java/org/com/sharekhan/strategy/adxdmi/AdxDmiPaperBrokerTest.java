package org.com.sharekhan.strategy.adxdmi;

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

class AdxDmiPaperBrokerTest {
    final LocalDateTime now=LocalDateTime.of(2026,11,9,10,1);
    final Instant instant=now.atZone(AdxDmiRules.IST).toInstant();
    final EntityManager em=mock(EntityManager.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final StrategyRunCoordinator runs=mock(StrategyRunCoordinator.class);
    final TradeAuditService audit=mock(TradeAuditService.class);
    final AdxDmiPaperBroker paper=new AdxDmiPaperBroker(em,trades,requests,new AdxDmiMarketData(quotes,mock(ScriptMasterRepository.class),mock(ShoonyaQuoteService.class),audit,Clock.fixed(instant,AdxDmiRules.IST)),runs,audit,Clock.fixed(instant,AdxDmiRules.IST));
    final StrategyApplyRequest r=new StrategyApplyRequest();
    final ScriptMasterEntity option=AdxDmiStrategyTest.option(123,"CE","10/11/2026",23000);
    final AdxDmiRules.Signal signal=new AdxDmiRules.Signal(1,now.withMinute(0),23001,22980,new AdxDmiRules.Reading(30,10,30));
    final List<TriggeredTradeSetupEntity> rows=new ArrayList<>();
    @BeforeEach void setup(){
        r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setLots(3);r.setSymbol("NIFTY");
        var broker=BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build();
        when(em.find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(broker);
        when(em.find(BrokerCredentialsEntity.class,2L)).thenReturn(broker);
        when(trades.findBySource(AdxDmiRules.SOURCE)).thenAnswer(c->List.copyOf(rows));
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23002)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,195,195,instant)));
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
                .source(AdxDmiRules.SOURCE).scripCode(123).spotScripCode(20000).symbol("NIFTY").optionType("CE").exchange("NF")
                .actualEntryPrice(101d).entryPrice(23002d).stopLoss(22980d).target1(23024d).quantity(65L).lots(1).intraday(true)
                .status(TriggeredTradeStatus.EXECUTED).entryAt(now.minusMinutes(1)).build();
        when(em.find(TriggeredTradeSetupEntity.class,22L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(t);rows.add(t);return t;
    }
    @Test void entryBuysAskAndRecordsSpotBracketSeparatelyWithLotMetadata(){
        var req=open();assertEquals(195L,req.getQuantity());assertEquals(23002,req.getEntryPrice());assertEquals(23024,req.getTarget1());
        var capture=org.mockito.ArgumentCaptor.forClass(TriggeredTradeSetupEntity.class);verify(trades,times(3)).saveAndFlush(capture.capture());
        var t=capture.getAllValues().getFirst();
        assertEquals(List.of(23024d,23046d,23068d),capture.getAllValues().stream().map(TriggeredTradeSetupEntity::getTarget1).toList());assertEquals(101,t.getActualEntryPrice());assertEquals(22980,t.getStopLoss());assertFalse(t.getTslEnabled());
        assertEquals("BUY",t.getEntryAction());assertEquals("CE",t.getOptionType());assertTrue(t.getOrderId().startsWith("SIM-ADXDMI-"));
        verify(em).find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE);verify(runs).attach(any());
    }
    @Test void mirroredPeIsStillBoughtAndUsesDownsideSpotTarget(){
        var pe=AdxDmiStrategyTest.option(123,"PE","10/11/2026",23000);
        var s=new AdxDmiRules.Signal(-1,signal.end(),23003,23020,new AdxDmiRules.Reading(10,30,30));
        var req=paper.open(r,s,pe,20000,List.of(pe));assertEquals("PE",req.getOptionType());assertEquals("BUY",req.getEntryAction());
        assertEquals(22984,req.getTarget1());assertEquals(23020,req.getStopLoss());
    }
    @Test void expiredSignalAndStaleOrShallowBooksNeverCreateRequests(){
        var expired=new AdxDmiRules.Signal(1,signal.end().minusMinutes(5),23001,22980,new AdxDmiRules.Reading(30,10,30));
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
        var closer=AdxDmiStrategyTest.option(124,"CE","10/11/2026",23002);
        assertThrows(IllegalStateException.class,()->paper.open(r,signal,option,20000,List.of(option,closer)));verify(requests,never()).save(any());
    }
    @Test void liveAccountCannotUsePaperEntryEvenByCallingTheBrokerDirectly(){
        when(em.find(BrokerCredentialsEntity.class,2L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build());
        assertThrows(IllegalArgumentException.class,this::open);verifyNoInteractions(requests);
    }
    @Test void cancelledSubscriptionAndClosingRunCannotRaceIntoAnEntry(){
        r.setSubscriptionId(8L);
        when(em.find(StrategySubscriptionEntity.class,8L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(StrategySubscriptionEntity.builder()
                .appUserId(1L).brokerCredentialsId(2L).templateId(AdxDmiRules.TEMPLATE).status("PAUSED").build());
        assertThrows(IllegalStateException.class,this::open);
        r.setSubscriptionId(null);
        when(em.find(StrategyRun.class,"run-1",LockModeType.PESSIMISTIC_WRITE)).thenReturn(StrategyRun.builder().id("run-1").appUserId(1L).brokerCredentialsId(2L).status("CLOSING").build());
        assertThrows(IllegalStateException.class,this::open);verify(requests,never()).save(any());
    }
    @Test void stopUsesCurrentSpotTickAndSellsBidWithoutWaitingForCandleClose(){
        var t=position();when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22979)));
        paper.evaluate(22L);assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals(100,t.getExitPrice());
        assertEquals("ADX_DMI_STOP",t.getExitReason());assertEquals(-65,t.getPnl());assertTrue(t.getEffectivePnl()<t.getPnl());
    }
    @Test void peStopIsUpwardAndPeTargetIsDownward(){
        var t=position();t.setOptionType("PE");t.setEntryPrice(23002d);t.setStopLoss(23020d);t.setTarget1(22984d);
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22983)));paper.evaluate(22L);
        assertEquals("ADX_DMI_TARGET",t.getExitReason());assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
    }
    @Test void observedSpotStopPersistsWhileBookIsStaleAndCannotDisappearOnRecovery(){
        var t=position();when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22979)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,instant.minusSeconds(6))));paper.evaluate(22L);
        assertEquals(TriggeredTradeStatus.EXECUTED,t.getStatus());assertEquals("ADX_DMI_STOP",t.getExitReason());
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(99,100,65,65,instant)));when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23010)));
        paper.evaluate(22L);assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals("ADX_DMI_STOP",t.getExitReason());assertEquals(99,t.getExitPrice());
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
        var late=new AdxDmiPaperBroker(em,trades,requests,new AdxDmiMarketData(quotes,mock(ScriptMasterRepository.class),mock(ShoonyaQuoteService.class),audit,Clock.fixed(now.withHour(15).withMinute(20).atZone(AdxDmiRules.IST).toInstant(),AdxDmiRules.IST)),runs,audit,Clock.fixed(now.withHour(15).withMinute(20).atZone(AdxDmiRules.IST).toInstant(),AdxDmiRules.IST));
        var at=now.withHour(15).withMinute(20).atZone(AdxDmiRules.IST).toInstant();
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(100,101,65,65,at)));late.evaluate(22L);
        assertEquals("ADX_DMI_SESSION_CLOSE",t.getExitReason());assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());
    }
    @Test void partialTargetsKeepEveryStopAtOriginalAndDoNotReuseDisplayedBidDepth(){
        var one=position();one.setId(22L);one.setTarget1(23024d);
        var two=TriggeredTradeSetupEntity.builder().id(23L).appUserId(1L).brokerCredentialsId(2L).source(AdxDmiRules.SOURCE)
                .symbol("NIFTY").scripCode(123).spotScripCode(20000).optionType("CE").entryPrice(23002d).actualEntryPrice(101d)
                .stopLoss(22980d).target1(23046d).quantity(65L).lots(1).exchange("NF").entryAt(now.minusMinutes(1)).status(TriggeredTradeStatus.EXECUTED).build();
        rows.add(two);when(em.find(TriggeredTradeSetupEntity.class,23L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(two);
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(110,111,65,195,instant)));
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23050)));
        paper.evaluate(22L);paper.evaluate(23L);
        assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,one.getStatus());assertEquals(TriggeredTradeStatus.EXECUTED,two.getStatus());
        assertEquals("ADX_DMI_TARGET",two.getExitReason());assertEquals(22980,two.getStopLoss());
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(book(109,110,130,195,instant)));
        paper.evaluate(23L);assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,two.getStatus());assertEquals(22980,two.getStopLoss());
    }
    @Test void sensexUsesBseIdentityAndItsActualLotSize(){
        r.setSymbol("SENSEX");option.setTradingSymbol("SENSEX");option.setExchange("BF");option.setLotSize(20);option.setTickSize(null);
        var spot=QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(23002d).lastLtpAt(instant)
                .ltpSource("SHAREKHAN_WS").ltpExchange("BC").ltpProviderIdentity("scrip:20000").build();
        var book=QuoteCacheService.QuoteSnapshot.builder().scripCode(123).bestBid(100d).bestAsk(101d).bidQuantity(60d).askQuantity(60d)
                .coherentBook(true).lastBookAt(instant).bookSource("SHAREKHAN_WS").bookExchange("BF").bookProviderIdentity("scrip:123").build();
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot));when(quotes.getSnapshot(123)).thenReturn(Optional.of(book));
        var req=open();assertEquals("SENSEX",req.getSymbol());assertEquals(60,req.getQuantity());
        var capture=org.mockito.ArgumentCaptor.forClass(TriggeredTradeSetupEntity.class);verify(trades,times(3)).saveAndFlush(capture.capture());
        capture.getAllValues().forEach(t->{assertEquals(20,t.getQuantity());assertEquals("BF",t.getExchange());assertEquals(22980,t.getStopLoss());});
    }
    @Test void verifiedRestFallbackCanOpenAndExitSensexWithExactOptionDepth() {
        var scripts=mock(ScriptMasterRepository.class);var api=mock(ShoonyaQuoteService.class);
        var spotMaster=ScriptMasterEntity.builder().scripCode(999901).exchange("BC").tradingSymbol("SENSEX").build();
        var contract=ScriptMasterEntity.builder().scripCode(321).exchange("BF").tradingSymbol("SENSEX").strikePrice(81900d)
                .optionType("CE").expiry("12/11/2026").lotSize(20).build();
        when(scripts.findByScripCode(999901)).thenReturn(spotMaster);when(scripts.findByScripCode(321)).thenReturn(contract);
        when(api.getExactQuote(spotMaster)).thenReturn(Optional.of(new ShoonyaQuoteService.ExactQuote(
                new ShoonyaQuoteService.LiveQuote("SENSEX","1","SENSEX","1",81879.4,null,null),"BSE",instant,instant)));
        when(api.getExactQuote(contract)).thenReturn(Optional.of(new ShoonyaQuoteService.ExactQuote(
                new ShoonyaQuoteService.LiveQuote("SENSEX26N1281900CE","provider-321","SENSEX26N1281900CE","provider-321",440d,439d,440d,60d,60d),"BFO",instant,instant)));
        var data=new AdxDmiMarketData(quotes,scripts,api,audit,Clock.fixed(instant,AdxDmiRules.IST));
        var broker=new AdxDmiPaperBroker(em,trades,requests,data,runs,audit,Clock.fixed(instant,AdxDmiRules.IST));
        r.setSymbol("SENSEX");
        var signal=new AdxDmiRules.Signal(1,now.withMinute(0),81800,81700,new AdxDmiRules.Reading(30,10,30));
        var req=broker.open(r,signal,contract,999901,List.of(contract));
        assertEquals(60,req.getQuantity());assertEquals(81879.4,req.getEntryPrice());
        var saved=org.mockito.ArgumentCaptor.forClass(TriggeredTradeSetupEntity.class);
        verify(trades,times(3)).saveAndFlush(saved.capture());assertTrue(saved.getAllValues().stream().allMatch(t->t.getActualEntryPrice()==440d && t.getQuantity()==20L && t.getStopLoss()==81700d));
        var t=saved.getAllValues().getFirst();when(em.find(TriggeredTradeSetupEntity.class,22L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(t);
        broker.close(22L,"STRATEGY_MANUAL_CLOSE");assertEquals(TriggeredTradeStatus.EXITED_SUCCESS,t.getStatus());assertEquals(439,t.getExitPrice());
    }
}
