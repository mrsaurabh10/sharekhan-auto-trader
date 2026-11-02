package org.com.sharekhan.backtest.openingrange;

import org.com.sharekhan.backtest.csv.CsvCandleReader;
import org.com.sharekhan.strategy.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpeningRangeBacktestTest {
    private static final LocalDate DAY = LocalDate.of(2026,10,2);
    private ArrayList<StrategyCandle> bars() {
        var result = new ArrayList<StrategyCandle>();
        for (int i=0;i<75;i++) result.add(bar(DAY, i,100,101,99,100));
        return result;
    }
    private StrategyCandle bar(LocalDate day,int i,double o,double h,double l,double c) {
        return new StrategyCandle(day,LocalTime.of(9,15).plusMinutes(i*5L),o,h,l,c,0L);
    }
    private IndicatorService bullish() {
        var service = mock(IndicatorService.class);
        when(service.computeEma(anyList(),eq(10))).thenAnswer(call -> {
            int n=((List<?>)call.getArgument(0)).size(); double[] values=new double[n];
            Arrays.fill(values,102); values[19]=99; return values;
        });
        when(service.computeEma(anyList(),eq(20))).thenAnswer(call -> {
            double[] values=new double[((List<?>)call.getArgument(0)).size()]; Arrays.fill(values,100); return values;
        });
        when(service.computeSupertrend(anyList(),anyInt(),anyDouble())).thenAnswer(call -> {
            double[] values=new double[((List<?>)call.getArgument(0)).size()]; Arrays.fill(values,95); return values;
        });
        return service;
    }
    private OpeningRangeBacktest.Report run(List<StrategyCandle> bars,IndicatorService service,OpeningRangeBacktest.Config c) {
        return new OpeningRangeBacktest(service).run(new CsvCandleReader.Dataset(bars,bars.size(),0,0,0),c);
    }
    private ArrayList<StrategyCandle> setup() {
        var all=bars();
        // Warm-up is from the preceding full session. Crossover happens on its 21st 15m candle.
        for(int i=0;i<75;i++) all.set(i,bar(DAY.minusDays(1),i,100,101,99,100));
        var today=bars();
        for(int i=7;i<75;i++) today.set(i,bar(DAY,i,100,101,100,100));
        today.set(5,bar(DAY,5,100,103,99,102)); // 09:30–09:45 breakout close
        today.set(6,bar(DAY,6,102,103,101,102));
        all.addAll(today); return all;
    }
    @Test void longUsesOppositeOpeningRangeStopAndAtLeastTwoR() {
        var bars=setup(); bars.set(75+7,bar(DAY,7,102,109,101,108));
        var r=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"));
        assertThat(r.trades()).hasSize(1); var t=r.trades().get(0);
        assertThat(t.entryAt()).isEqualTo(DAY.atTime(9,45)); assertThat(t.signalAt()).isEqualTo(t.entryAt());
        assertThat(t.stop()).isEqualTo(99); assertThat(t.target()).isEqualTo(108);
        assertThat(t.reason()).isEqualTo("TARGET"); assertThat(t.netPoints()).isEqualTo(6);
    }
    @Test void simultaneousTouchesStopFirstIncludingEntryBar() {
        var bars=setup(); bars.set(81,bar(DAY,6,102,110,98,100));
        var t=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY")).trades().get(0);
        assertThat(t.reason()).isEqualTo("STOP"); assertThat(t.netPoints()).isEqualTo(-3);
    }
    @Test void gapsThroughStopFillAtWorseOpen() {
        var bars=setup(); bars.set(82,bar(DAY,7,97,100,96,98));
        var t=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY")).trades().get(0);
        assertThat(t.reason()).isEqualTo("STOP_GAP"); assertThat(t.exit()).isEqualTo(97);
    }
    @Test void squareOffNeverUsesLaterPriceOrCarriesOvernight() {
        var bars=setup(); bars.set(75+73,bar(DAY,73,100,1000,1,500));
        var r=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"));
        assertThat(r.trades()).hasSize(1); var t=r.trades().get(0);
        assertThat(t.reason()).isEqualTo("SQUARE_OFF"); assertThat(t.exitBarAt()).isEqualTo(DAY.atTime(15,20));
        assertThat(t.exit()).isEqualTo(100); assertThat(t.entryAt().toLocalDate()).isEqualTo(t.exitBarAt().toLocalDate());
    }
    @Test void missingOpeningOrLateBarRejectsSessionInsteadOfInventingExit() {
        for(int slot:List.of(0,73)) {
            var bars=setup(); bars.remove(75+slot);
            var r=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"));
            assertThat(r.trades()).isEmpty(); assertThat(r.exclusions()).hasSize(1);
        }
    }
    @Test void conflictsEqualityAndUnobservedCrossNeverSignal() {
        assertThat(OpeningRangeBacktest.direction(100,102,101,99,102,100,105,1)).isZero();
        assertThat(OpeningRangeBacktest.direction(100,102,101,99,102,100,95,0)).isZero();
        assertThat(OpeningRangeBacktest.direction(100,101,101,99,102,100,95,1)).isZero();
        assertThat(OpeningRangeBacktest.direction(102,103,101,99,102,100,95,1)).isZero();
        assertThat(OpeningRangeBacktest.direction(100,98,101,99,98,100,105,-1)).isEqualTo(-1);
        assertThat(OpeningRangeBacktest.direction(100,98,101,99,98,100,95,-1)).isZero();
    }
    @Test void dailyLimitSharedAcrossRepeatedBreakoutsAndResetsTomorrow() {
        var bars=setup();
        for(int i:List.of(6,12,18)) {
            bars.set(75+i-1,bar(DAY,i-1,100,103,99,102));
            bars.set(75+i,bar(DAY,i,102,110,101,108));
        }
        var r=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"));
        assertThat(r.trades()).hasSize(2);
        var c=new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,2,1,0,0,LocalTime.of(15,20));
        assertThat(run(bars,bullish(),c).trades()).hasSize(1);
        var tomorrow=new ArrayList<StrategyCandle>();
        for(var b:bars.subList(75,150)) tomorrow.add(new StrategyCandle(DAY.plusDays(3),b.time(),b.open(),b.high(),b.low(),b.close(),0L));
        bars.addAll(tomorrow);
        var all=run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"));
        assertThat(all.trades()).hasSize(4);
    }
    @Test void indicatorsAreCausalAndKnownEmaSeedIsCorrect() {
        var bars=setup(); var svc=new IndicatorService();
        var ema=svc.computeEma(bars,10); assertThat(ema[9]).isEqualTo(100);
        var st=svc.computeSupertrend(bars,10,3);
        var prefix=bars.subList(0,90);
        assertThat(Arrays.copyOf(ema,90)).containsExactly(svc.computeEma(prefix,10));
        assertThat(Arrays.copyOf(st,90)).containsExactly(svc.computeSupertrend(prefix,10,3));
        assertThatThrownBy(()->svc.computeEma(bars,0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shortExecutionMirrorsLongAndStopsAtOpeningHigh() {
        var reflected=new ArrayList<StrategyCandle>();
        var source=setup(); source.set(82,bar(DAY,7,102,109,101,108));
        for(var b:source) reflected.add(new StrategyCandle(b.date(),b.time(),200-b.open(),200-b.low(),200-b.high(),200-b.close(),0L));
        var svc=bullish();
        when(svc.computeEma(anyList(),eq(10))).thenAnswer(call->{
            double[] v=new double[((List<?>)call.getArgument(0)).size()]; Arrays.fill(v,98); v[19]=101; return v;
        });
        when(svc.computeSupertrend(anyList(),anyInt(),anyDouble())).thenAnswer(call->{
            double[] v=new double[((List<?>)call.getArgument(0)).size()]; Arrays.fill(v,105); return v;
        });
        var t=run(reflected,svc,OpeningRangeBacktest.Config.defaults("BANKNIFTY")).trades().get(0);
        assertThat(t.direction()).isEqualTo("SHORT"); assertThat(t.stop()).isEqualTo(101);
        assertThat(t.target()).isEqualTo(92); assertThat(t.exit()).isEqualTo(92); assertThat(t.netPoints()).isEqualTo(6);
    }
    @Test void frictionAppliesToBothFillsAndCostsOnce() {
        var bars=setup(); bars.set(82,bar(DAY,7,102,112,101,108));
        var c=new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,2,2,0.5,1,LocalTime.of(15,20));
        var t=run(bars,bullish(),c).trades().get(0);
        assertThat(t.entry()).isEqualTo(102.5); assertThat(t.target()).isEqualTo(109.5);
        assertThat(t.exit()).isEqualTo(109); assertThat(t.netPoints()).isEqualTo(5.5);
    }
    @Test void breakoutGapBackInsideAndCutoffEntriesAreSkipped() {
        var bars=setup(); bars.set(81,bar(DAY,6,100,103,99,102));
        assertThat(run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY")).trades()).isEmpty();
        bars=setup(); bars.set(80,bar(DAY,5,100,101,99,100));
        bars.set(146,bar(DAY,71,100,103,99,102)); // 15:00–15:15 close
        var c=new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,2,2,0,0,LocalTime.of(15,15));
        assertThat(run(bars,bullish(),c).trades()).isEmpty();
    }
    @Test void invalidParametersAndDuplicatesFailClosed() {
        assertThatThrownBy(()->new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,1,2,0,0,LocalTime.of(15,20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,2,3,0,0,LocalTime.of(15,20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OpeningRangeBacktest.Config("NIFTY",null,null,10,3,2,2,0,0,LocalTime.of(15,30)))
                .isInstanceOf(IllegalArgumentException.class);
        var bars=setup(); bars.add(1,bars.get(0));
        assertThatThrownBy(()->run(bars,bullish(),OpeningRangeBacktest.Config.defaults("NIFTY"))).isInstanceOf(IllegalArgumentException.class);
    }
}
