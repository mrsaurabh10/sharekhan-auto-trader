package org.com.sharekhan.strategy.run;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/** Rupee controls for one run. Price/indicator stops on individual legs remain independent. */
@Embeddable @Getter @Setter @NoArgsConstructor
public class StrategyRiskSettings {
    public enum AmountMode { TOTAL, PER_LOT }
    @Enumerated(EnumType.STRING) @Column(name="risk_amount_mode",length=16) private AmountMode amountMode;
    @Column(name="risk_profit_target",precision=19,scale=2) private BigDecimal profitTarget;
    @Column(name="risk_max_loss",precision=19,scale=2) private BigDecimal maxLoss;
    @Column(name="risk_trail_activation",precision=19,scale=2) private BigDecimal trailActivation;
    @Column(name="risk_trail_giveback",precision=19,scale=2) private BigDecimal trailGiveback;
    @Column(name="risk_sl_step_profit",precision=19,scale=2) private BigDecimal slStepProfit;
    @Column(name="risk_sl_step_raise",precision=19,scale=2) private BigDecimal slStepRaise;

    public static StrategyRiskSettings validate(StrategyRiskSettings input,Integer lots) {
        if(input==null) return null;
        var result=new StrategyRiskSettings();result.amountMode=input.amountMode==null?AmountMode.TOTAL:input.amountMode;
        if(result.amountMode==AmountMode.PER_LOT && (lots==null || lots<1)) throw new IllegalArgumentException("Per-lot run controls require option lots; use Total for shares");
        result.profitTarget=amount(input.profitTarget);result.maxLoss=amount(input.maxLoss);
        result.trailActivation=amount(input.trailActivation);result.trailGiveback=amount(input.trailGiveback);
        result.slStepProfit=amount(input.slStepProfit);result.slStepRaise=amount(input.slStepRaise);
        if((result.trailActivation==null)!=(result.trailGiveback==null)) throw new IllegalArgumentException("Set both profit-trail activation and giveback");
        if(result.trailGiveback!=null && result.trailGiveback.compareTo(result.trailActivation)>=0) throw new IllegalArgumentException("Profit-trail giveback must be smaller than activation");
        if((result.slStepProfit==null)!=(result.slStepRaise==null) || (result.slStepProfit!=null && result.maxLoss==null)) throw new IllegalArgumentException("Trailing stop requires maximum loss, profit step and stop raise");
        if(result.slStepRaise!=null && result.slStepRaise.compareTo(result.slStepProfit)>0) throw new IllegalArgumentException("Stop raise must not exceed its profit step");
        if(result.profitTarget!=null && result.trailActivation!=null && result.profitTarget.compareTo(result.trailActivation)<=0) throw new IllegalArgumentException("Profit target must exceed trailing activation, or disable one rule");
        return result.profitTarget==null && result.maxLoss==null && result.trailActivation==null ? null : result;
    }
    public static BigDecimal amount(BigDecimal value) {
        if(value==null || value.signum()==0) return null;
        if(value.signum()<0 || value.stripTrailingZeros().scale()>2 || value.compareTo(new BigDecimal("999999999.99"))>0)
            throw new IllegalArgumentException("Run risk amounts must be positive rupees with at most two decimals, or blank/zero to disable");
        return value.setScale(2);
    }
    public BigDecimal scale(BigDecimal amount,Integer lots) {
        return amount==null?null:amount.multiply(BigDecimal.valueOf(amountMode==AmountMode.PER_LOT?lots:1));
    }
}
