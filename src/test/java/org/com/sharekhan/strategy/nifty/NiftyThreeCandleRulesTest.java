package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.strategy.StrategyCandle;
import org.com.sharekhan.cache.QuoteCacheService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NiftyThreeCandleRulesTest {
    static final LocalDate DAY=LocalDate.of(2026,11,9);
    static List<StrategyCandle> candles(boolean pe){
        var out=new ArrayList<StrategyCandle>();
        for(int d=0;d<3;d++)for(int i=0;i<75;i++)out.add(new StrategyCandle(LocalDate.of(2026,11,4).plusDays(d),LocalTime.of(9,15).plusMinutes(5L*i),100,100.2,99.8,100,null));
        for(int i=0;i<3;i++){
            double open=100+(pe?-i:i),close=open+(pe?-1:1);
            out.add(new StrategyCandle(DAY,LocalTime.of(9,15).plusMinutes(5L*i),open,Math.max(open,close)+.2,Math.min(open,close)-.2,close,null));
        }
        return out;
    }
    @Test void diagnosticsDistinguishWarmupGapsAndMixedCandleColors() {
        var bars=candles(false);var now=DAY.atTime(9,30);
        assertTrue(NiftyThreeCandleRules.check(bars.subList(30,bars.size()),now).reason().contains("198/200"));
        bars.remove(bars.size()-2);
        assertTrue(NiftyThreeCandleRules.check(bars,now).reason().contains("gap; expected 09:20"));
        bars=candles(false);bars.set(bars.size()-1,new StrategyCandle(DAY,LocalTime.of(9,25),102,103,101,102,null));
        assertTrue(NiftyThreeCandleRules.check(bars,now).reason().contains("not all green or all red"));
    }
    @Test void frozenCeAndMirroredPeNeedNoVolumeOrBodyFilters(){
        var ce=NiftyThreeCandleRules.signal(candles(false),DAY.atTime(9,30)).orElseThrow();
        assertEquals("CE",ce.side());assertEquals(103.25,ce.trigger(),1e-9);assertEquals(101.8,ce.stop(),1e-9);
        assertTrue(ce.ema()>ce.priorEma());assertEquals(104.7,ce.target(103.25),1e-9);
        var pe=NiftyThreeCandleRules.signal(candles(true),DAY.atTime(9,30)).orElseThrow();
        assertEquals("PE",pe.side());assertEquals(96.75,pe.trigger(),1e-9);assertEquals(98.2,pe.stop(),1e-9);
        assertTrue(pe.ema()<pe.priorEma());assertEquals(95.3,pe.target(96.75),1e-9);
        assertTrue(pe.crossed(96.75));assertFalse(pe.crossed(96.8));assertTrue(ce.crossed(103.25));
    }
    @Test void currentCandleNeverInfluencesTheSignalAndFourthWindowExpires(){
        var bs=candles(false);var now=DAY.atTime(9,31);
        var expected=NiftyThreeCandleRules.signal(bs,now);
        bs.add(new StrategyCandle(DAY,LocalTime.of(9,30),1000,1100,900,950,null));
        assertEquals(expected,NiftyThreeCandleRules.signal(bs,now));
        assertTrue(expected.orElseThrow().inWindow(DAY.atTime(9,34,59)));
        assertFalse(expected.orElseThrow().inWindow(DAY.atTime(9,35)));
        assertTrue(NiftyThreeCandleRules.signal(candles(false),DAY.atTime(9,35)).isEmpty());
    }
    @Test void missingWarmupGapDojiAndConflictingDuplicatesWait(){
        var bs=candles(false);assertTrue(NiftyThreeCandleRules.signal(bs.subList(30,bs.size()),DAY.atTime(9,30)).isEmpty());
        bs.remove(bs.size()-2);assertTrue(NiftyThreeCandleRules.signal(bs,DAY.atTime(9,30)).isEmpty());
        bs=candles(false);bs.set(bs.size()-1,new StrategyCandle(DAY,LocalTime.of(9,25),102,103,101,102,null));
        assertTrue(NiftyThreeCandleRules.signal(bs,DAY.atTime(9,30)).isEmpty());
        bs=candles(false);bs.add(new StrategyCandle(DAY,LocalTime.of(9,25),102,104,101,103,null));
        assertTrue(NiftyThreeCandleRules.signal(bs,DAY.atTime(9,30)).isEmpty());
    }
    @Test void rollingSignalsKeepTheSameColourRunAndIgnoreTheFormingOppositeCandle(){
        for(boolean pe:List.of(false,true)){
            var bs=candles(pe);double direction=pe?-1:1;
            var first=NiftyThreeCandleRules.signal(bs,DAY.atTime(9,30)).orElseThrow();
            for(int i=3;i<5;i++){
                double open=100+direction*i,close=open+direction;
                bs.add(new StrategyCandle(DAY,LocalTime.of(9,15).plusMinutes(5L*i),open,Math.max(open,close)+.2,Math.min(open,close)-.2,close,null));
            }
            var rolling=NiftyThreeCandleRules.signal(bs,DAY.atTime(9,40)).orElseThrow();
            assertNotEquals(first.key(),rolling.key());
            assertEquals(DAY.atTime(9,15),rolling.colourRunStart());
            bs.add(new StrategyCandle(DAY,LocalTime.of(9,40),110,111,89,90,null));
            assertEquals(rolling,NiftyThreeCandleRules.signal(bs,DAY.atTime(9,41)).orElseThrow());
        }
    }
    @Test void completedOppositeOrDojiStartsANewRunWithoutChangingBreakoutRules(){
        for(boolean pe:List.of(false,true))for(boolean doji:List.of(false,true)){
            var bs=candles(pe);double direction=pe?-1:1;
            double resetClose=doji?100:100-direction;
            bs.add(new StrategyCandle(DAY,LocalTime.of(9,30),100,102,98,resetClose,null));
            for(int i=0;i<3;i++){
                double open=100+direction*(10+i),close=open+direction;
                bs.add(new StrategyCandle(DAY,LocalTime.of(9,35).plusMinutes(5L*i),open,Math.max(open,close)+.2,Math.min(open,close)-.2,close,null));
            }
            var signal=NiftyThreeCandleRules.signal(bs,DAY.atTime(9,50)).orElseThrow();
            assertEquals(DAY.atTime(9,35),signal.colourRunStart());
            var c=bs.getLast();
            assertEquals(pe?c.low()-.05:c.high()+.05,signal.trigger(),1e-8);
            assertEquals(pe?c.high():c.low(),signal.stop(),1e-8);
        }
    }
    static QuoteCacheService.QuoteSnapshot book(Instant at){return QuoteCacheService.QuoteSnapshot.builder().scripCode(123).bestBid(100d).bestAsk(101d)
            .coherentBook(true).bookSource("SHAREKHAN_WS").bookExchange("NF").bookProviderIdentity("scrip:123").lastBookAt(at).build();}
    @Test void onlyFreshCoherentExactExchangeBooksQualify(){
        var now=Instant.parse("2026-11-09T04:01:00Z");
        assertTrue(NiftyThreeCandleRules.freshBook(book(now),now,123));
        assertFalse(NiftyThreeCandleRules.freshBook(book(now.minusSeconds(6)),now,123));
        assertFalse(NiftyThreeCandleRules.freshBook(book(now.plusSeconds(1)),now,123));
        assertFalse(NiftyThreeCandleRules.freshBook(book(now),now,124));
        assertFalse(NiftyThreeCandleRules.freshBook(QuoteCacheService.QuoteSnapshot.builder().scripCode(123).bestBid(101d).bestAsk(100d).coherentBook(true)
                .bookSource("SHAREKHAN_WS").bookExchange("NF").bookProviderIdentity("scrip:123").lastBookAt(now).build(),now,123));
    }
}
