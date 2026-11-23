package org.com.sharekhan.strategy.nifty;

import java.time.*;
import java.util.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NiftyThreeCandleTrailingTest {
    final LocalDate day=LocalDate.of(2026,11,23);
    TriggeredTradeSetupEntity trade(boolean pe){return TriggeredTradeSetupEntity.builder()
            .source(NiftyThreeCandleRules.SOURCE).entryAt(day.atTime(9,35,3)).entryPrice(100d)
            .stopLoss(pe?160d:40d).target1(pe?40d:160d).optionType(pe?"PE":"CE").build();}
    StrategyCandle bar(int minute,double low,double high){return new StrategyCandle(day,LocalTime.of(9,minute),(low+high)/2,high,low,(low+high)/2,null);}
    List<StrategyCandle> bars(boolean pe){return pe?List.of(bar(35,80,90),bar(40,82,95),bar(45,80,92))
            :List.of(bar(35,110,120),bar(40,105,118),bar(45,108,120));}
    @Test void candleHistoryCanDependBackOnThePositionManagerWithoutAStartupCycle(){
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.registerBean(NiftyThreeCandleTrailing.class);
            context.registerBean(org.com.sharekhan.repository.ScriptMasterRepository.class,
                    ()->org.mockito.Mockito.mock(org.com.sharekhan.repository.ScriptMasterRepository.class));
            context.registerBean(NiftyThreeCandleMarketData.class,()->{
                assertNotNull(context.getBean(NiftyThreeCandleTrailing.class));
                return org.mockito.Mockito.mock(NiftyThreeCandleMarketData.class);
            });
            context.refresh();assertNotNull(context.getBean(NiftyThreeCandleTrailing.class));
        }
    }
    @Test void onlyRiskStrictlyAbove50AndExactStrategyQualifies(){
        var t=trade(false);t.setStopLoss(50d);assertFalse(NiftyThreeCandleTrailing.eligible(t));
        t.setStopLoss(49.95);assertTrue(NiftyThreeCandleTrailing.eligible(t));
        t.setEntryPrice(25566.7);t.setStopLoss(25516.7);assertFalse(NiftyThreeCandleTrailing.eligible(t));
        t.setSource("other");assertFalse(NiftyThreeCandleTrailing.eligible(t));
    }
    @Test void ceAndPeWaitForRightBarCloseThenUseTwoPointBuffer(){
        for(boolean pe:List.of(false,true)){
            var t=trade(pe);assertNull(NiftyThreeCandleTrailing.candidate(t,bars(pe),day.atTime(9,49,59)));
            var c=NiftyThreeCandleTrailing.candidate(t,bars(pe),day.atTime(9,50));assertNotNull(c);
            assertEquals(pe?97:103,c.stop());assertEquals(day.atTime(9,50),c.confirmed());
            assertEquals(pe?160:40,t.getStopLoss());assertEquals(pe?40:160,t.getTarget1());
        }
    }
    @Test void profitOnlyAndNeverLoosenAfterRestart(){
        for(boolean pe:List.of(false,true)){
            var t=trade(pe);t.setTrailingSl(pe?96d:104d);
            assertNull(NiftyThreeCandleTrailing.candidate(t,bars(pe),day.atTime(9,50)));
            assertEquals(pe?96:104,NiftyThreeCandleTrailing.activeStop(t));
            t.setEntryPrice(pe?96d:104d);t.setTrailingSl(null);
            assertNull(NiftyThreeCandleTrailing.candidate(t,bars(pe),day.atTime(9,50)));
        }
    }
    @Test void pivotsBeforeEntryTiesAndMissingNeighbourDoNotQualify(){
        var t=trade(false);t.setEntryAt(day.atTime(9,40,1));
        assertNull(NiftyThreeCandleTrailing.candidate(t,bars(false),day.atTime(9,50)));
        t=trade(false);assertNull(NiftyThreeCandleTrailing.candidate(t,List.of(bar(35,105,120),bar(40,105,118),bar(45,108,120)),day.atTime(9,50)));
        assertNull(NiftyThreeCandleTrailing.candidate(t,List.of(bar(30,110,120),bar(40,105,118),bar(45,108,120)),day.atTime(9,50)));
    }
    @Test void conflictingOrInvalidBarsFailClosed(){
        var input=new ArrayList<>(bars(false));input.add(bar(40,104,118));
        assertNull(NiftyThreeCandleTrailing.candidate(trade(false),input,day.atTime(9,50)));
        input=new ArrayList<>(bars(false));input.add(new StrategyCandle(day,LocalTime.of(9,25),100,90,105,100,null));
        assertNull(NiftyThreeCandleTrailing.candidate(trade(false),input,day.atTime(9,50)));
    }
    @Test void irrelevantOrNonProfitStoredTrailingValuesCannotOverrideOriginalStop(){
        var t=trade(false);t.setTrailingSl(99d);assertEquals(40,NiftyThreeCandleTrailing.activeStop(t));
        t.setTrailingSl(105d);t.setStopLoss(60d);assertEquals(60,NiftyThreeCandleTrailing.activeStop(t));
    }
}
