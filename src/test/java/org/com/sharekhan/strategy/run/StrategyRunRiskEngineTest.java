package org.com.sharekhan.strategy.run;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class StrategyRunRiskEngineTest {
    final StrategyRunRiskEngine engine=new StrategyRunRiskEngine();
    BigDecimal d(String v){return new BigDecimal(v);}
    StrategyRiskSettings settings(){var r=new StrategyRiskSettings();r.setAmountMode(StrategyRiskSettings.AmountMode.PER_LOT);return r;}
    @Test void combinedProfitAndLossScaleByCapturedRunLotsAndTriggerAtEquality() {
        var r=settings();r.setProfitTarget(d("2000"));r.setMaxLoss(d("1000"));
        assertThat(engine.evaluate(r,2,d("3999.99"),null,null).closeReason()).isNull();
        assertThat(engine.evaluate(r,2,d("4000"),null,null).closeReason()).isEqualTo("STRATEGY_PROFIT_TARGET");
        assertThat(engine.evaluate(r,2,d("-2000"),null,null).closeReason()).isEqualTo("STRATEGY_MAX_LOSS");
    }
    @Test void profitTrailArmsAtActivationAndPeakAndFloorNeverRetreatAfterRestart() {
        var r=settings();r.setAmountMode(StrategyRiskSettings.AmountMode.TOTAL);r.setTrailActivation(d("3000"));r.setTrailGiveback(d("1000"));
        var before=engine.evaluate(r,1,d("2999"),null,null);assertThat(before.floor()).isNull();
        var peak=engine.evaluate(r,1,d("5000"),before.peak(),before.floor());assertThat(peak.floor()).isEqualByComparingTo("4000");
        var fall=engine.evaluate(r,1,d("4000"),peak.peak(),peak.floor());assertThat(fall.closeReason()).isEqualTo("STRATEGY_TRAILING_STOP");
        assertThat(fall.peak()).isEqualByComparingTo("5000");assertThat(fall.floor()).isEqualByComparingTo("4000");
    }
    @Test void steppedLossStopTightensOnProfitIncrementsAndCombinesWithProfitTrail() {
        var r=settings();r.setMaxLoss(d("2000"));r.setSlStepProfit(d("1000"));r.setSlStepRaise(d("500"));
        var a=engine.evaluate(r,2,d("3999"),null,null);assertThat(a.floor()).isEqualByComparingTo("-3000");
        var b=engine.evaluate(r,2,d("4000"),a.peak(),a.floor());assertThat(b.floor()).isEqualByComparingTo("-2000");
        var c=engine.evaluate(r,2,d("0"),b.peak(),b.floor());assertThat(c.floor()).isEqualByComparingTo("-2000");assertThat(c.closeReason()).isNull();
    }
    @Test void disabledControlsDoNotCauseExitAndTotalAmountsIgnoreLots() {
        assertThat(engine.evaluate(null,1,d("-9999"),null,null).closeReason()).isNull();
        var r=settings();r.setAmountMode(StrategyRiskSettings.AmountMode.TOTAL);r.setProfitTarget(d("2000"));
        assertThat(engine.evaluate(r,10,d("2000"),null,null).closeReason()).isEqualTo("STRATEGY_PROFIT_TARGET");
    }
    @Test void rejectsInvalidAmbiguousAndSharePerLotRules() {
        var r=settings();r.setProfitTarget(d("-1"));assertThatThrownBy(()->StrategyRiskSettings.validate(r,1)).isInstanceOf(IllegalArgumentException.class);
        r.setProfitTarget(d("2000"));r.setTrailActivation(d("3000"));r.setTrailGiveback(d("1000"));assertThatThrownBy(()->StrategyRiskSettings.validate(r,1)).isInstanceOf(IllegalArgumentException.class);
        r.setProfitTarget(null);assertThat(StrategyRiskSettings.validate(r,1)).isNotSameAs(r);
        assertThatThrownBy(()->StrategyRiskSettings.validate(r,null)).isInstanceOf(IllegalArgumentException.class);
        r.setTrailGiveback(null);assertThatThrownBy(()->StrategyRiskSettings.validate(r,1)).isInstanceOf(IllegalArgumentException.class);
    }
}
