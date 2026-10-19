package org.com.sharekhan.strategy.run;
import java.math.*;

/** Pure run-level gross-P&L rules, also suitable for replay using synchronized marks. */
public final class StrategyRunRiskEngine {
    public record Decision(BigDecimal peak,BigDecimal floor,String closeReason) { }
    public Decision evaluate(StrategyRiskSettings rules,Integer lots,BigDecimal pnl,BigDecimal oldPeak,BigDecimal oldFloor) {
        BigDecimal peak=oldPeak==null?pnl:oldPeak.max(pnl);
        BigDecimal floor=oldFloor;
        if(rules==null) return new Decision(peak,floor,null);
        var loss=rules.scale(rules.getMaxLoss(),lots);
        if(loss!=null) floor=max(floor,loss.negate());
        var step=rules.scale(rules.getSlStepProfit(),lots);
        if(step!=null && peak.signum()>0) floor=max(floor,loss.negate().add(peak.divideToIntegralValue(step).multiply(rules.scale(rules.getSlStepRaise(),lots))));
        var activation=rules.scale(rules.getTrailActivation(),lots);
        if(activation!=null && peak.compareTo(activation)>=0) floor=max(floor,peak.subtract(rules.scale(rules.getTrailGiveback(),lots)));
        String reason=null;
        if(floor!=null && pnl.compareTo(floor)<=0) reason=loss!=null && floor.compareTo(loss.negate())==0?"STRATEGY_MAX_LOSS":"STRATEGY_TRAILING_STOP";
        var target=rules.scale(rules.getProfitTarget(),lots);
        if(reason==null && target!=null && pnl.compareTo(target)>=0) reason="STRATEGY_PROFIT_TARGET";
        return new Decision(peak,floor,reason);
    }
    private BigDecimal max(BigDecimal a,BigDecimal b) {return a==null?b:a.max(b);}
}
