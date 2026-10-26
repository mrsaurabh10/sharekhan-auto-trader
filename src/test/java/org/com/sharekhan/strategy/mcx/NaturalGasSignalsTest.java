package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.Test;
import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasSignalsTest {
    static final LocalDate DAY=LocalDate.of(2026,10,26);
    static final LocalDateTime NOW=DAY.atTime(11,20,5);
    static List<StrategyCandle> candles(boolean bullish) {
        List<StrategyCandle> rows=new ArrayList<>();
        for(int d=0;d<2;d++)for(int i=0;i<174;i++) {
            double p=100+d*20+i*.1;
            rows.add(bar(DAY.minusDays(d==0?4:3),LocalTime.of(9,0).plusMinutes(i*5),p-.05,p+.2,p-.2,p,100L));
        }
        for(int i=0;i<26;i++){double p=150+i*.1;rows.add(bar(DAY,LocalTime.of(9,0).plusMinutes(i*5),p-.05,p+.2,p-.2,p,100L));}
        rows.add(bar(DAY,LocalTime.of(11,10),152.6,153.1,152.5,153,100L));
        rows.add(bar(DAY,LocalTime.of(11,15),152.7,153,152.6,152.9,100L));
        if(!bullish)rows=rows.stream().map(c->bar(c.date(),c.time(),400-c.open(),400-c.low(),400-c.high(),400-c.close(),c.volume())).toList();
        return new ArrayList<>(rows);
    }
    private static StrategyCandle bar(LocalDate d,LocalTime t,double o,double h,double l,double c,Long v){return new StrategyCandle(d,t,o,h,l,c,v);}
    @Test void bullishBreakoutRetestBuysCe(){var s=NaturalGasSignals.evaluate(candles(true),NOW,.1).orElseThrow();assertEquals("CE",s.side());assertEquals(153.1,s.trigger(),1e-8);assertTrue(s.stop()<s.trigger());}
    @Test void bearishBreakoutRetestBuysPe(){var s=NaturalGasSignals.evaluate(candles(false),NOW,.1).orElseThrow();assertEquals("PE",s.side());assertTrue(s.stop()>s.trigger());}
    @Test void incompleteRetestNeverSignals(){assertTrue(NaturalGasSignals.evaluate(candles(true),DAY.atTime(11,19,59),.1).isEmpty());}
    @Test void staleCompletedSignalWaits(){assertTrue(NaturalGasSignals.evaluate(candles(true),NOW.plusMinutes(2),.1).isEmpty());}
    @Test void missingSessionBarFailsClosed(){var rows=candles(true);rows.remove(rows.size()-10);assertTrue(NaturalGasSignals.evaluate(rows,NOW,.1).isEmpty());}
    @Test void missingVolumeDoesNotBypassVwap(){var rows=candles(true);var c=rows.getLast();rows.set(rows.size()-1,bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),null));assertTrue(NaturalGasSignals.evaluate(rows,NOW,.1).isEmpty());}
    @Test void duplicateTimestampsFailClosed(){var rows=candles(true);rows.add(rows.getLast());assertTrue(NaturalGasSignals.evaluate(rows,NOW,.1).isEmpty());}
    @Test void sessionStartsAtNineAndHasBufferedCutoffs(){assertFalse(NaturalGasSession.entryAllowed(DAY.atTime(8,59)));assertTrue(NaturalGasSession.entryAllowed(DAY.atTime(9,0)));assertTrue(NaturalGasSession.entryAllowed(DAY.atTime(22,59,59)));assertFalse(NaturalGasSession.entryAllowed(DAY.atTime(23,0)));assertTrue(NaturalGasSession.marketOpen(DAY.atTime(23,29,59)));assertFalse(NaturalGasSession.marketOpen(DAY.atTime(23,30)));assertFalse(NaturalGasSession.entryAllowed(DAY.minusDays(1).atTime(12,0)));}
    @Test void neverUsesIncompleteFutureBar(){var rows=candles(true);rows.add(bar(DAY,LocalTime.of(11,20),152.9,160,150,155,100L));assertEquals(NaturalGasSignals.evaluate(candles(true),NOW,.1),NaturalGasSignals.evaluate(rows,NOW,.1));}
    @Test void explainsStaleHistoryWithoutClaimingSignalFailure() {
        var rows=candles(true);rows.removeIf(c->c.date().equals(DAY));
        assertTrue(NaturalGasSignals.inspect(rows,NOW,.1).reason().startsWith("No MCX candles for today"));
    }
    @Test void explainsMissingOpeningCaptureInsteadOfTradingPartialSession() {
        var rows=candles(true);rows.removeIf(c->c.date().equals(DAY)&&c.time().equals(LocalTime.of(9,0)));
        var result=NaturalGasSignals.inspect(rows,NOW,.1);assertTrue(result.signal().isEmpty());assertTrue(result.reason().startsWith("Incomplete MCX session"));
    }
    @Test void explainsStaleCompletedCandleAndMissingVolume() {
        assertTrue(NaturalGasSignals.inspect(candles(true),NOW.plusMinutes(2),.1).reason().startsWith("Stale MCX candle"));
        var rows=candles(true);var c=rows.getLast();rows.set(rows.size()-1,bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),null));
        assertTrue(NaturalGasSignals.inspect(rows,NOW,.1).reason().startsWith("Missing or zero MCX interval volume"));
    }
}
