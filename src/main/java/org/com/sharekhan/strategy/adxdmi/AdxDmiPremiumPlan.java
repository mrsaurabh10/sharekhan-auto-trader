package org.com.sharekhan.strategy.adxdmi;
import java.math.*;

/** Frozen, equal premium R brackets. Both CE and PE are BUY orders. */
public record AdxDmiPremiumPlan(double entry,double stop,double t1,double t2,double t3,double risk,double delta) {
    public static AdxDmiPremiumPlan create(double premium,double spotRisk,double delta,double tick){
        if(!Double.isFinite(premium) || !Double.isFinite(spotRisk) || !Double.isFinite(delta) || !Double.isFinite(tick)
                || premium<=0 || spotRisk<=0 || delta<=0 || delta>1 || tick<=0)throw new IllegalArgumentException("Invalid premium bracket input");
        BigDecimal increment=BigDecimal.valueOf(tick),entryTicks=BigDecimal.valueOf(premium).divide(increment,0,RoundingMode.CEILING);
        BigDecimal riskTicks=BigDecimal.valueOf(spotRisk).multiply(BigDecimal.valueOf(delta)).divide(increment,0,RoundingMode.CEILING).max(BigDecimal.ONE);
        double entry=entryTicks.multiply(increment).doubleValue(),risk=riskTicks.multiply(increment).doubleValue();
        double stop=entryTicks.subtract(riskTicks).multiply(increment).doubleValue();
        if(stop<=0)throw new IllegalArgumentException("Delta-based option SL is non-positive; no order submitted");
        return new AdxDmiPremiumPlan(entry,stop,entryTicks.add(riskTicks).multiply(increment).doubleValue(),entryTicks.add(riskTicks.multiply(BigDecimal.TWO)).multiply(increment).doubleValue(),entryTicks.add(riskTicks.multiply(BigDecimal.valueOf(3))).multiply(increment).doubleValue(),risk,delta);
    }
}
