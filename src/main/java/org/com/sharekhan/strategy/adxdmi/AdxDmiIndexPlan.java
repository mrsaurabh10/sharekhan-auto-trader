package org.com.sharekhan.strategy.adxdmi;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Option entry is a premium; all exit levels are in underlying price units. */
public record AdxDmiIndexPlan(double entry, double spotEntry, double stop, double t1, double t2, double t3) {
    public static AdxDmiIndexPlan create(double ask, double spot, AdxDmiRules.Signal signal, double optionTick) {
        if (!Double.isFinite(ask) || ask<=0 || !Double.isFinite(spot) || spot<=0
                || !Double.isFinite(optionTick) || optionTick<=0 || !signal.crossed(spot) || signal.risk(spot)<=0)
            throw new IllegalArgumentException("Invalid index exit plan");
        double premium=BigDecimal.valueOf(ask).divide(BigDecimal.valueOf(optionTick),0,RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(optionTick)).doubleValue();
        return new AdxDmiIndexPlan(premium,spot,signal.stop(),signal.target(spot,1),signal.target(spot,2),signal.target(spot,3));
    }
}
