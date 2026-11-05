package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasPineSignalsTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,11,4,18,35,5);
    static List<StrategyCandle> candles(boolean bull) {
        var rows=new ArrayList<StrategyCandle>();
        for(int day=6;day>=0;day--) {
            var date=NOW.toLocalDate().minusDays(day);
            if(date.getDayOfWeek()==DayOfWeek.SATURDAY || date.getDayOfWeek()==DayOfWeek.SUNDAY)continue;
            var end=day==0?NOW.withSecond(0):date.atTime(23,0);
            for(var t=date.atTime(9,0);t.isBefore(end);t=t.plusMinutes(5)) {
                double close=day==0 && t.equals(NOW.withSecond(0).minusMinutes(5))?(bull?304:296):300;
                rows.add(new StrategyCandle(date,t.toLocalTime(),300,Math.max(300,close)+.1,Math.min(300,close)-.1,close,100L));
            }
        }
        return rows;
    }
    @Test void originalCrossoverQualifiesBothDirectionsWithAllFilters() {
        var p=new NaturalGasPinePolicy();p.afterPropertiesSet();
        var longSignal=NaturalGasPineSignals.inspect(candles(true),NOW,p);
        assertTrue(longSignal.ready(),longSignal.reason());assertEquals("CE",longSignal.side());assertTrue(longSignal.fresh(NOW));
        assertEquals("PE",NaturalGasPineSignals.inspect(candles(false),NOW,p).side());
        assertFalse(longSignal.fresh(NOW.plusSeconds(76)));
    }
    @Test void emaAndAtrUsePineInitializationAndWilderSmoothing() {
        assertArrayEquals(new double[]{1,1.5,2.25},NaturalGasPineSignals.ema(List.of(1d,2d,3d),3),1e-12);
        var rows=new ArrayList<StrategyCandle>();
        for(int i=0;i<15;i++)rows.add(new StrategyCandle(NOW.toLocalDate(),LocalTime.of(9,0).plusMinutes(i*5),100,101+(i==14?14:0),99,100,1L));
        var atr=NaturalGasPineSignals.atr(rows);
        assertEquals(2,atr[13],1e-12);assertEquals(3,atr[14],1e-12);
    }
    @Test void rejectsSessionGapsDuplicatesMissingOpeningAndMissingVolume() {
        var p=new NaturalGasPinePolicy();var rows=new ArrayList<>(candles(true));
        rows.removeIf(c->c.date().equals(NOW.toLocalDate()) && c.time().equals(LocalTime.of(10,0)));
        assertFalse(NaturalGasPineSignals.inspect(rows,NOW,p).ready());
        rows=new ArrayList<>(candles(true));rows.add(rows.getLast());assertFalse(NaturalGasPineSignals.inspect(rows,NOW,p).ready());
        rows=new ArrayList<>(candles(true));rows.removeIf(c->c.date().equals(NOW.toLocalDate()) && c.time().equals(LocalTime.of(9,0)));
        assertFalse(NaturalGasPineSignals.inspect(rows,NOW,p).ready());
        rows=new ArrayList<>(candles(true));var last=rows.removeLast();rows.add(new StrategyCandle(last.date(),last.time(),last.open(),last.high(),last.low(),last.close(),null));
        assertFalse(NaturalGasPineSignals.inspect(rows,NOW,p).ready());
    }
    @Test void formingChartAndHourlyBarsCannotChangeConfirmedSignal() {
        var rows=new ArrayList<>(candles(true));var p=new NaturalGasPinePolicy();var baseline=NaturalGasPineSignals.inspect(rows,NOW,p);
        rows.add(new StrategyCandle(NOW.toLocalDate(),LocalTime.of(18,35),304,1000,1,1,10000L));
        assertEquals(baseline,NaturalGasPineSignals.inspect(rows,NOW,p));
        var map=new TreeMap<LocalDateTime,StrategyCandle>();
        for(int m=0;m<60;m+=5)map.put(NOW.withHour(17).withMinute(m).withSecond(0),new StrategyCandle(NOW.toLocalDate(),LocalTime.of(17,m),300,301,299,300,1L));
        assertTrue(NaturalGasPineSignals.hourlyCloses(map,NOW.withHour(17).withMinute(55).withSecond(0)).isEmpty());
        assertEquals(List.of(300d),NaturalGasPineSignals.hourlyCloses(map,NOW.withHour(18).withMinute(0).withSecond(0)));
        map.remove(map.firstKey());assertTrue(NaturalGasPineSignals.hourlyCloses(map,NOW).isEmpty());
    }
    @Test void hourlyWarmupNeverUsesFiveMinuteClosesAsSubstitute() {
        var rows=candles(true).stream().filter(c->c.date().equals(NOW.toLocalDate())).toList();
        assertTrue(NaturalGasPineSignals.inspect(rows,NOW,new NaturalGasPinePolicy()).reason().startsWith("Hourly EMA50 warmup"));
    }
    @Test void dstAndDatedNewsWindowsAndSessionBoundariesAreExplicit() {
        var p=new NaturalGasPinePolicy();
        assertTrue(p.blocked(LocalDateTime.of(2026,10,29,19,50)));
        assertFalse(p.blocked(LocalDateTime.of(2026,10,29,20,15)));
        assertTrue(p.blocked(LocalDateTime.of(2026,11,5,20,50)));
        assertFalse(p.blocked(LocalDateTime.of(2026,11,5,21,15)));
        assertFalse(p.entryAllowed(NOW.withHour(18).withMinute(29)));
        assertTrue(p.entryAllowed(NOW));assertFalse(p.entryAllowed(NOW.withHour(23).withMinute(15)));
        var block=new NaturalGasPinePolicy.Blackout();block.setStart(LocalDateTime.of(2026,11,25,22,20));block.setEnd(block.getStart().plusMinutes(25));
        p.setBlackouts(List.of(block));p.afterPropertiesSet();assertTrue(p.blocked(block.getStart()));assertFalse(p.blocked(block.getEnd()));
        p.setSquareOff(LocalTime.of(23,30));assertThrows(IllegalArgumentException.class,p::afterPropertiesSet);
    }
    @Test void optionalPullbackIsDistinctFromOriginalCrossover() {
        var p=new NaturalGasPinePolicy();p.setEntryMode(NaturalGasPinePolicy.EntryMode.PULLBACK);
        assertNull(NaturalGasPineSignals.inspect(candles(true),NOW,p).side());
    }
}
