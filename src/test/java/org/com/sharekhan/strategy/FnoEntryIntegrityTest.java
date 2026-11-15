package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FnoEntryIntegrityTest {
    final LocalDate day=LocalDate.of(2026,11,13);
    final StrategySupport support=mock(StrategySupport.class);
    final Fno925EntryQualificationService service=new Fno925EntryQualificationService(support);
    final Fno925Candidate candidate=new Fno925Candidate("TEST",ScriptMasterEntity.builder().exchange("NC").scripCode(123).build(),"CE");
    List<StrategyCandle> bars=new ArrayList<>();
    FnoEntryIntegrityTest() {
        for(int i=0;i<75;i++)bars.add(new StrategyCandle(day.minusDays(1),LocalTime.of(9,15).plusMinutes(5L*i),100,101,99,100,10000L));
        add(9,15,100,101,99,100);add(9,20,100,101,99.5,100.5);add(9,25,100.5,101,100,100.7);add(9,30,100.7,103,100.5,102.5);
        when(support.roundPrice(anyDouble())).thenAnswer(a->Math.round(((Double)a.getArgument(0))*100d)/100d);
        when(support.loadCandlesWithHistoricalFallback(any(),eq(77))).thenAnswer(a->new CandleLoad(List.copyOf(bars),true,null));
        ReflectionTestUtils.setField(service,"orbVolumeMultiplier",.75d);ReflectionTestUtils.setField(service,"baseVolumeMultiplier",1d);
        ReflectionTestUtils.setField(service,"volumeLookback",5);ReflectionTestUtils.setField(service,"maxOpposingWickToRange",.65d);
        ReflectionTestUtils.setField(service,"minBodyToRange",.30d);ReflectionTestUtils.setField(service,"maxRiskAtrMultiplier",2d);
        ReflectionTestUtils.setField(service,"vwapSideTolerance",.001d);
    }
    void add(int h,int m,double o,double hi,double lo,double c) {bars.add(new StrategyCandle(day,LocalTime.of(h,m),o,hi,lo,c,20000L));}
    Fno925EntryQualificationService.Qualification evaluate(LocalDateTime now) {
        var clock=Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE);
        ReflectionTestUtils.setField(service,"clock",clock);ReflectionTestUtils.setField(service,"sharedDecisions",new SharedMarketDecisions(clock));
        var result=new Fno925EntryQualificationService.Qualification[1];
        service.withinCycle(new Fno925EntryQualificationService.EvaluationCycle(now),()->result[0]=service.qualifyMoverWithFreshRetest(candidate,now));
        return result[0];
    }
    @Test void publicPathRejectsMissingExpectedBarEvenWhenCycleAndSharedDecisionAreFresh() {
        assertThat(evaluate(day.atTime(9,35,10)).qualified()).isTrue();
        for(var time:List.of(day.atTime(9,40),day.atTime(10,15,10))) {
            var result=evaluate(time);assertThat(result.qualified()).isFalse();
            assertThat(result.reason()).contains("expected latest completed");
        }
    }
    @Test void formingFutureAndOutsideSessionBarsCannotChangeCompletedSignalOrAtr() {
        var baseline=evaluate(day.atTime(9,35,10));assertThat(baseline.qualified()).isTrue();
        add(9,35,102.5,140,60,105);add(9,40,105,160,50,110);
        bars.add(new StrategyCandle(day.minusDays(1),LocalTime.of(15,30),100,200,1,100,999999L));
        bars.add(new StrategyCandle(day,LocalTime.of(9,10),100,200,1,100,999999L));
        bars.add(new StrategyCandle(day.plusDays(1),LocalTime.of(9,30),100,200,1,100,999999L));
        var result=evaluate(day.atTime(9,35,10));
        assertThat(result.qualified()).isTrue();assertThat(result.signal()).isEqualTo(baseline.signal());
        assertThat(result.signal().atr()).isEqualTo(1.99);
        assertThat(result.retestFrame().atr()).isCloseTo(1.992,within(.01));
    }
    @Test void barBecomesEligibleExactlyAtItsCloseAndReplacesPreviousSignal() {
        add(9,35,102.5,103,102.4,102.8);
        assertThat(evaluate(day.atTime(9,39,59)).signal().breakoutCandle().time()).isEqualTo(LocalTime.of(9,30));
        var closed=evaluate(day.atTime(9,40));assertThat(closed.qualified()).isTrue();
        assertThat(closed.signal().breakoutCandle().time()).isEqualTo(LocalTime.of(9,35));
    }
    @Test void unfinishedBarCannotCompleteAtrWarmup() {
        bars=new ArrayList<>(bars.subList(bars.size()-75,bars.size()));add(9,35,102.5,140,60,105);
        var result=evaluate(day.atTime(9,35,10));assertThat(result.qualified()).isFalse();
        assertThat(result.reason()).contains("ATR(75) is unavailable");
        verify(support).loadCandlesWithHistoricalFallback(any(),eq(77));
    }
    @ParameterizedTest @ValueSource(strings={"CE","PE"})
    void rejectsBreakoutDirectionRejectionWickAndAllowsOppositeEndWick(String side) {
        boolean ce=side.equals("CE");
        var reference=new StrategyCandle(day,LocalTime.of(9,25),ce?90:120,ce?90:120,ce?90:120,ce?90:120,10000L);
        var bad=new StrategyCandle(day,LocalTime.of(9,30),ce?100:110,110,100,ce?103:107,10000L);
        var good=new StrategyCandle(day,LocalTime.of(9,30),ce?107:103,110,100,ce?110:100,10000L);
        Optional<String> rejected=ReflectionTestUtils.invokeMethod(service,"filterFailure",List.of(reference,bad),bad,side,List.of(reference),.75d);
        Optional<String> accepted=ReflectionTestUtils.invokeMethod(service,"filterFailure",List.of(reference,good),good,side,List.of(reference),.75d);
        assertThat(rejected).contains("breakout candle has an immediate rejection wick");assertThat(accepted).isEmpty();
    }
}
