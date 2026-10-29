package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FnoFreshRetestIntegrationTest {
    final LocalDate day=LocalDate.of(2026,10,29);
    final StrategySupport support=mock(StrategySupport.class);
    final Fno925EntryQualificationService service=new Fno925EntryQualificationService(support);
    final Fno925Candidate candidate=new Fno925Candidate("ABC",ScriptMasterEntity.builder().exchange("NC").scripCode(123).build(),"CE");
    final List<StrategyCandle> bars=new ArrayList<>();
    FnoFreshRetestIntegrationTest() {
        when(support.roundPrice(anyDouble())).thenAnswer(i->Math.round((double)i.getArgument(0)*100)/100d);
        when(support.loadCandlesWithHistoricalFallback(any(),eq(76))).thenAnswer(i->new CandleLoad(List.copyOf(bars),true,null));
        ReflectionTestUtils.setField(service,"orbVolumeMultiplier",.75);
        ReflectionTestUtils.setField(service,"baseVolumeMultiplier",1d);
        ReflectionTestUtils.setField(service,"volumeLookback",5);
        ReflectionTestUtils.setField(service,"maxOpposingWickToRange",.65);
        ReflectionTestUtils.setField(service,"minBodyToRange",.30);
        ReflectionTestUtils.setField(service,"maxRiskAtrMultiplier",2d);
        ReflectionTestUtils.setField(service,"vwapSideTolerance",.001);
        for(int i=0;i<75;i++)bars.add(new StrategyCandle(day.minusDays(1),LocalTime.of(9,15).plusMinutes(5L*i),99.5,100,99,99.5,100L));
        add(9,15,99,100,90,99.5,100);
        add(9,20,99.5,100,90,99.5,100);
        for(int m=25;m<=55;m+=5)add(9,m,99.5,100,99,99.5,100);
        add(10,0,99,102,98,101.5,200);
    }
    void add(int h,int m,double o,double hi,double lo,double c,long volume) {bars.add(new StrategyCandle(day,LocalTime.of(h,m),o,hi,lo,c,volume));}
    void clock(int minute) {ReflectionTestUtils.setField(service,"clock",Clock.fixed(day.atTime(10,minute).atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));}
    Fno925EntryQualificationService.Qualification qualify(int minute) {
        clock(minute);return service.qualifyMoverWithFreshRetest(candidate,day.atTime(10,minute));
    }
    void completeRetest() {add(10,5,101.5,101.6,100.1,100.6,100);add(10,10,100.6,102,100.5,101.8,200);}
    @Test void actualRiskRejectionThenRetestUsesNewPathWhileStockAndManualPathsKeepBaseline() {
        var anchor=qualify(5);
        assertThat(anchor.qualified()).isFalse();assertThat(anchor.retestFrame().riskOnlyRejection()).isTrue();
        completeRetest();
        var result=qualify(15);
        assertThat(result.qualified()).isTrue();assertThat(result.signal().setup()).isEqualTo("MORNING_ORB_FRESH_RETEST");
        assertThat(result.signal().stopLoss()).isEqualTo(100.1);
        assertThat(service.qualifyWithDirectionalCandle(candidate,day.atTime(10,15)).qualified()).isFalse();
        assertThat(service.qualify(candidate,day.atTime(10,15)).qualified()).isFalse();
    }
    @Test void disabledSwitchCannotAddEntryAndRestartRequiresANewObservedAnchor() {
        qualify(5);completeRetest();ReflectionTestUtils.setField(service,"freshRetestEnabled",false);
        assertThat(qualify(15).qualified()).isFalse();
        var restarted=new Fno925EntryQualificationService(support);
        // A restart cannot recreate a previously observed rejection from bars alone.
        ReflectionTestUtils.setField(restarted,"clock",Clock.fixed(day.atTime(10,15).atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
        assertThat(restarted.qualifyMoverWithFreshRetest(candidate,day.atTime(10,15)).qualified()).isFalse();
    }
    @Test void formingAndFutureBarsAreExcludedFromRetestInputsAndCannotConfirm() {
        qualify(5);add(10,5,101.5,101.6,100.1,100.6,100);add(10,10,100.6,102,100.5,101.8,200);
        var waiting=qualify(10);assertThat(waiting.qualified()).isFalse();
        assertThat(waiting.retestFrame().candles()).noneMatch(c->c.time().equals(LocalTime.of(10,10)));
    }
    @Test void expiredCycleDoesNotSeedOrPublishRetest() {
        clock(5);var cycle=new Fno925EntryQualificationService.EvaluationCycle(day.atTime(10,4));
        service.withinCycle(cycle,()->assertThat(service.qualifyMoverWithFreshRetest(candidate,day.atTime(10,5)).reason()).contains("expired"));
        completeRetest();assertThat(qualify(15).qualified()).isFalse();
    }
    @Test void lowVolumeConfirmationRemainsRejectedByRealFilters() {
        qualify(5);add(10,5,101.5,101.6,100.1,100.6,100);add(10,10,100.6,102,100.5,101.8,10);
        assertThat(qualify(15).qualified()).isFalse();
    }
}
