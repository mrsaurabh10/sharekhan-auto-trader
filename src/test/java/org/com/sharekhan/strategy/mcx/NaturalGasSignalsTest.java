package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.Test;
import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasSignalsTest {
    static NaturalGasSignals.Evaluation fiveMinute(List<StrategyCandle> rows,LocalDateTime now) {
        return NaturalGasSignals.inspect(rows,now,.1,NaturalGasDataMode.ROLLING_WINDOW,NaturalGasTrendTimeframe.FIVE_MINUTE);
    }
    static double metric(NaturalGasSignals.Evaluation evaluation,String key) {
        return Arrays.stream(evaluation.dataContext().split("; ")).filter(s->s.startsWith(key+"="))
                .mapToDouble(s->Double.parseDouble(s.substring(key.length()+1))).findFirst().orElseThrow();
    }
    static List<StrategyCandle> risingFiveMinuteBars(int count) {
        var rows=new ArrayList<StrategyCandle>();
        for(int i=0;i<count;i++) {
            double p=100+i*.1;
            rows.add(bar(DAY,LocalTime.of(9,0).plusMinutes(i*5),p-.05,p+.2,p-.2,p,100L));
        }
        return rows;
    }
    @Test void fiveMinuteTrendUsesEachCompletedCloseAndThreeFiveMinuteBarsForSlope() {
        var rows=risingFiveMinuteBars(60);var now=DAY.atTime(14,0,5);
        var e=fiveMinute(rows,now);
        assertEquals(104.95,metric(e,"EMA20"),1e-9);
        assertEquals(103.45,metric(e,"EMA50"),1e-9);
        assertEquals(104.65,metric(e,"EMA20ThreeBarsAgo"),1e-9);
        assertEquals(.3,metric(e,"EMA20SlopeChange"),1e-9);
        assertEquals(105.9,metric(e,"futuresClose"),1e-9);
        assertEquals(104.75,e.vwap(),1e-9);
        assertTrue(e.dataContext().contains("trendTimeframe=FIVE_MINUTE"));
        assertTrue(e.reason().startsWith("MCX trend aligned"));
        assertTrue(flexible(rows,now).reason().startsWith("Insufficient completed MCX history"));
        var later=risingFiveMinuteBars(61);var updated=fiveMinute(later,now.plusMinutes(5));
        assertEquals(105.05,metric(updated,"EMA20"),1e-9);
        assertEquals(103.55,metric(updated,"EMA50"),1e-9);
        // The 14:00-14:05 close updates the trend without waiting for 14:15.
        assertEquals(106.0,metric(updated,"futuresClose"),1e-9);
    }
    @Test void fiveMinuteHistoryNeedsFiftyThreeBarsAndNeverUsesTheFormingBar() {
        var rows=risingFiveMinuteBars(53);var now=DAY.atTime(13,25,5);
        var baseline=fiveMinute(rows,now);
        assertEquals(104.25,metric(baseline,"EMA20"),1e-9);
        rows.add(bar(DAY,LocalTime.of(13,25),105,1000,1,900,100L));
        assertEquals(baseline,fiveMinute(rows,now));
        assertTrue(fiveMinute(risingFiveMinuteBars(52),now.minusMinutes(5)).reason().contains("52/53"));
    }
    @Test void fiveMinuteModePreservesBreakoutRetestBothDirectionsAndDataGuards() {
        for(boolean bull:List.of(true,false)) {
            var now=DAY.atTime(19,0,5);var rows=lateCandles(bull,LocalTime.of(17,0));
            assertEquals(bull?"CE":"PE",fiveMinute(rows,now).signal().orElseThrow().side());
            assertTrue(fiveMinute(rows,now.plusMinutes(2)).reason().startsWith("Stale MCX candle"));
            var c=rows.getLast();rows.add(c);
            assertTrue(fiveMinute(rows,now).reason().startsWith("Duplicate MCX"));rows.removeLast();
            rows.removeIf(x->x.date().equals(DAY)&&x.time().equals(LocalTime.of(18,0)));
            assertTrue(fiveMinute(rows,now).reason().contains("11/24"));
        }
    }
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
    static List<StrategyCandle> lateCandles(boolean bullish, LocalTime start) {
        var original=candles(bullish);var rows=new ArrayList<StrategyCandle>();
        rows.addAll(original.stream().filter(c->!c.date().equals(DAY)).toList());
        var session=original.stream().filter(c->c.date().equals(DAY)).toList();
        for(int i=0;i<24;i++){var c=session.get(i+4);rows.add(bar(DAY,start.plusMinutes(i*5),c.open(),c.high(),c.low(),c.close(),c.volume()));}
        return rows;
    }
    static NaturalGasSignals.Evaluation flexible(List<StrategyCandle> rows,LocalDateTime now){return NaturalGasSignals.inspect(rows,now,.1,NaturalGasDataMode.ROLLING_WINDOW);}
    @Test void lateStartWithTwentyFourContiguousBarsCanSignalBothDirections() {
        var evening=DAY.atTime(19,0,5);
        for(boolean bull:List.of(true,false)) {
            var rows=lateCandles(bull,LocalTime.of(17,0));var e=flexible(rows,evening);
            assertEquals(bull?"CE":"PE",e.signal().orElseThrow().side());
            assertTrue(e.dataContext().contains("ROLLING_WINDOW"));
            assertTrue(e.dataContext().contains("2026-10-26T17:00/2026-10-26T19:00 (24 bars)"));
            assertTrue(e.dataContext().contains("missing groups omitted"));
            assertTrue(NaturalGasSignals.inspect(rows,evening,.1).reason().startsWith("Incomplete MCX session"));
        }
    }
    @Test void twentyThreeBarsNeverBorrowPriorSessionCandlesForWarmup() {
        var rows=lateCandles(true,LocalTime.of(17,0));rows.removeLast();
        var e=flexible(rows,DAY.atTime(18,55,5));assertTrue(e.signal().isEmpty());
        assertTrue(e.reason().contains("23/24"));assertTrue(e.reason().contains("19:00"));assertNull(e.vwap());
    }
    @Test void gapRecoversOnlyAfterTwentyFourConsecutiveBarsAfterIt() {
        var rows=lateCandles(true,LocalTime.of(17,0));
        var old=bar(DAY,LocalTime.of(14,0),149,150,148,149,null);rows.add(old);
        var complete=flexible(rows,DAY.atTime(19,0,5));assertTrue(complete.signal().isPresent());
        var missing=rows.stream().filter(c->!c.date().equals(DAY)||!c.time().equals(LocalTime.of(17,0))).toList();
        var incomplete=flexible(missing,DAY.atTime(19,0,5));assertTrue(incomplete.signal().isEmpty());
        assertTrue(incomplete.reason().contains("23/24"));assertTrue(incomplete.reason().contains("19:05"));
        var insideGap=new ArrayList<>(rows);insideGap.removeIf(c->c.date().equals(DAY)&&c.time().equals(LocalTime.of(18,0)));
        assertTrue(flexible(insideGap,DAY.atTime(19,0,5)).reason().contains("11/24"));
    }
    @Test void rollingVwapUsesOnlySelectedBarsWithActualVolumeWeights() {
        var rows=lateCandles(true,LocalTime.of(17,0));var original=rows.getLast();
        rows.set(rows.size()-1,bar(original.date(),original.time(),original.open(),original.high(),original.low(),original.close(),300L));
        double sum=0;long volume=0;
        for(var c:rows)if(c.date().equals(DAY)){sum+=(c.high()+c.low()+c.close())/3*c.volume();volume+=c.volume();}
        var expected=flexible(rows,DAY.atTime(19,0,5));assertEquals(sum/volume,expected.vwap(),1e-9);
        rows.add(bar(DAY,LocalTime.of(14,0),200,201,199,200,1000000000L));
        var actual=flexible(rows,DAY.atTime(19,0,5));assertEquals(expected.vwap(),actual.vwap());assertEquals(expected.signal(),actual.signal());
        assertTrue(actual.dataContext().contains("rollingVWAPValue="));
    }
    @Test void currentWindowStillRejectsMissingVolumeStaleDataAndDuplicateBars() {
        var rows=lateCandles(true,LocalTime.of(17,0));var c=rows.getLast();
        rows.set(rows.size()-1,bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),null));
        assertTrue(flexible(rows,DAY.atTime(19,0,5)).reason().contains("rolling VWAP unavailable"));
        assertTrue(flexible(lateCandles(true,LocalTime.of(17,0)),DAY.atTime(19,2)).reason().startsWith("Stale MCX candle"));
        rows=lateCandles(true,LocalTime.of(17,0));rows.add(rows.getLast());assertTrue(flexible(rows,DAY.atTime(19,0,5)).reason().startsWith("Duplicate MCX"));
    }
    @Test void flexibleModeRetainsHistoryWarmupCutoffsAndCompletedCandleRule() {
        var rows=lateCandles(true,LocalTime.of(17,0));
        var baseline=flexible(rows,DAY.atTime(19,0,5));
        rows.add(bar(DAY,LocalTime.of(19,0),152.9,170,150,165,100L));
        assertEquals(baseline,flexible(rows,DAY.atTime(19,0,5)));
        assertTrue(flexible(rows,DAY.atTime(23,0)).reason().startsWith("Outside MCX"));
        rows.removeIf(c->!c.date().equals(DAY));assertTrue(flexible(rows,DAY.atTime(19,0,5)).reason().startsWith("Insufficient completed MCX history"));
    }

}
