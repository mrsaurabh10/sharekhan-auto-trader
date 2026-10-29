package org.com.sharekhan.analysis;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.com.sharekhan.analysis.SwingModels.*;

class SwingPlannerTest {
    final LocalDate day=LocalDate.of(2026,10,28),valid=day.plusDays(1);
    final SwingPlanner planner=new SwingPlanner();
    ScriptMasterEntity stock(){return ScriptMasterEntity.builder().scripCode(10).tradingSymbol("DEMO").exchange("NC").tickSize(.05).build();}
    List<HistoricalCandle> prices(){
        var rows=new ArrayList<HistoricalCandle>();
        for(int i=0;i<220;i++){double c=100+.2*i+1.4*Math.sin(i*Math.PI/2);rows.add(candle(day.minusDays(219-i),c-.1,c+.3,c-.3,c));}
        double[] closes={143,144,143.3,144.2,143.7};
        for(int i=0;i<5;i++){double c=closes[i];rows.set(215+i,candle(day.minusDays(4-i),c-.1,c+.3,c-.3,c));}return rows;
    }
    HistoricalCandle candle(LocalDate d,double o,double h,double l,double c){return new HistoricalCandle(d,LocalTime.MIDNIGHT,o,h,l,c,3_000_000L);}
    Plan plan(){return new Plan("DEMO",10,"NC","20_DAY_BREAKOUT","Shoonya",day,valid,100,101,95,113,119,6,6,3,55,300_000_000,"test",List.of());}
    @Test void plansUseWorstEntryForRiskAndTargetsAndTickAlignment(){
        var result=planner.evaluate(stock(),prices(),day,valid);assertThat(result.status()).as(result.detail()).isEqualTo("CANDIDATE");
        var p=result.plan();assertThat(p.riskPerShare()).isCloseTo(p.maxEntry()-p.stop(),within(.011));
        assertThat(p.target1()).isGreaterThanOrEqualTo(p.maxEntry()+2*p.riskPerShare());
        assertThat(p.target2()).isGreaterThanOrEqualTo(p.maxEntry()+3*p.riskPerShare());
        assertThat(p.riskPercent()).isLessThanOrEqualTo(8);
        for(double value:new double[]{p.entry(),p.maxEntry(),p.stop(),p.target1(),p.target2()})assertThat(value/.05).isCloseTo(Math.rint(value/.05),within(.000001));
    }
    @Test void futureCandleCannotChangeHistoricalPlan(){
        var before=planner.evaluate(stock(),prices(),day,valid);var rows=prices();rows.add(candle(day.plusDays(1),1000,2000,1,1900));
        assertThat(planner.evaluate(stock(),rows,day,valid)).isEqualTo(before);
    }
    @Test void staleMalformedAndConflictingDataFailClosed(){
        assertThat(planner.evaluate(stock(),prices(),day.plusDays(1),valid).status()).isEqualTo("UNAVAILABLE");
        var malformed=prices();malformed.add(candle(day,100,90,95,99));
        assertThat(planner.evaluate(stock(),malformed,day,valid).status()).isEqualTo("UNAVAILABLE");
        var conflicting=prices();conflicting.add(candle(day,140,145,138,144));
        assertThat(planner.evaluate(stock(),conflicting,day,valid).status()).isEqualTo("UNAVAILABLE");
    }
    @Test void missingTickAndVolumeDoNotBecomeTradePlans(){
        var stock=stock();stock.setTickSize(null);assertThat(planner.evaluate(stock,prices(),day,valid).plan()).isNull();
        var rows=prices();var c=rows.getLast();rows.set(219,new HistoricalCandle(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),-1L));
        assertThat(planner.evaluate(stock(),rows,day,valid).status()).isEqualTo("UNAVAILABLE");
    }
    @Test void observationDoesNotInferSameBarOrderingOrFills(){
        var obs=planner.observe(plan(),List.of(candle(valid,99,114,94,102)),valid);
        assertThat(obs.status()).isEqualTo("AMBIGUOUS");
        assertThat(planner.observe(plan(),List.of(candle(valid,100.5,114,99,112)),valid).status()).isEqualTo("TARGET_1_TOUCHED");
    }
    @Test void gapSkipExpiryAndOpeningInvalidationAreDistinct(){
        assertThat(planner.observe(plan(),List.of(candle(valid,102,114,99,112)),valid).status()).isEqualTo("GAP_SKIP");
        assertThat(planner.observe(plan(),List.of(candle(valid,98,99,97,98)),valid).status()).isEqualTo("EXPIRED");
        assertThat(planner.observe(plan(),List.of(candle(valid,94,114,93,112)),valid).status()).isEqualTo("INVALIDATED_BEFORE_ENTRY");
    }
    @Test void missingOutcomeSessionsStayUnavailable(){
        assertThat(planner.observe(plan(),List.of(candle(valid,100,105,99,103)),valid.plusDays(1)).status()).isEqualTo("UNAVAILABLE");
        assertThat(planner.observe(plan(),List.of(),day).status()).isEqualTo("WATCHLIST");
    }
}
