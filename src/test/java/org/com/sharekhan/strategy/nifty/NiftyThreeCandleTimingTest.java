package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NiftyThreeCandleTimingTest {
    static class MovingClock extends Clock {
        Instant at;
        MovingClock(Instant at){this.at=at;}
        public ZoneId getZone(){return NiftyThreeCandleRules.IST;}
        public Clock withZone(ZoneId zone){return Clock.fixed(at,zone);}
        public Instant instant(){return at;}
    }
    final LocalDateTime start=NiftyThreeCandleRulesTest.DAY.atTime(9,30);
    final MovingClock clock=new MovingClock(start.atZone(NiftyThreeCandleRules.IST).toInstant());
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final StrategySupport support=mock(StrategySupport.class);
    final NiftyThreeCandleMarketData data=mock(NiftyThreeCandleMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final TradeExecutionService feeds=mock(TradeExecutionService.class);
    final NiftyThreeCandlePaperBroker paper=mock(NiftyThreeCandlePaperBroker.class);
    final NseMarketCalendar calendar=mock(NseMarketCalendar.class);
    final NiftyThreeCandleStrategy strategy=new NiftyThreeCandleStrategy(brokers,scripts,support,data,quotes,feeds,paper,calendar,clock);
    final StrategyApplyRequest request=new StrategyApplyRequest();
    final ScriptMasterEntity option=NiftyThreeCandleStrategyTest.option(123,"CE","10/11/2026",100);
    NiftyThreeCandleTimingTest(){
        request.setUserId(1L);request.setBrokerCredentialsId(2L);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build()));
        when(calendar.isTradingDay(start.toLocalDate())).thenReturn(true);
        when(support.resolveSpotScript("NIFTY")).thenReturn(ScriptMasterEntity.builder().scripCode(20000).exchange("NC").build());
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("NF","NIFTY")).thenReturn(List.of(option));
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(c->StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
        when(paper.open(any(),any(),any(),anyInt(),anyList())).thenReturn(TriggerTradeRequestEntity.builder().id(1L).build());
    }
    QuoteCacheService.QuoteSnapshot spot(Instant at){return QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(104d)
            .lastLtpAt(at).ltpSource("SHAREKHAN_WS").ltpExchange("NC").ltpProviderIdentity("scrip:20000").build();}
    @Test void tickReceivedDuringCandleLoadCanEnter(){
        when(data.candles(any(),any())).thenAnswer(c->{clock.at=clock.at.plusSeconds(2);return NiftyThreeCandleRulesTest.candles(false);});
        when(quotes.getSnapshot(20000)).thenAnswer(c->{clock.at=clock.at.plusMillis(1);return Optional.of(spot(clock.at));});
        assertEquals("triggered",strategy.apply(request).getStatus());
        verify(paper).open(eq(request),any(),eq(option),eq(20000),eq(List.of(option)));
    }
    @Test void slowLoadCannotExtendFourthCandleWindow(){
        when(data.candles(any(),any())).thenAnswer(c->{clock.at=clock.at.plusSeconds(300);return NiftyThreeCandleRulesTest.candles(false);});
        when(quotes.getSnapshot(20000)).thenAnswer(c->Optional.of(spot(clock.at)));
        assertTrue(strategy.apply(request).getMessage().contains("window has expired"));
        verifyNoInteractions(paper);
    }
    @Test void staleAndFutureQuotesStillWait(){
        when(data.candles(any(),any())).thenReturn(NiftyThreeCandleRulesTest.candles(false));
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(clock.at.minusSeconds(6))));
        assertTrue(strategy.apply(request).getMessage().contains("fresh Nifty spot"));
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(clock.at.plusSeconds(1))));
        assertTrue(strategy.apply(request).getMessage().contains("fresh Nifty spot"));
        verifyNoInteractions(paper);
    }
}
