package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class Orb915930CeStrategy extends AbstractOrbStrategy {
    public Orb915930CeStrategy(StrategySupport support) {
        super(support, new StrategyMetadata(
                "ORB_915_930_CE",
                "ORB 9:15-9:30 CE",
                "First 15-minute range breakout above ORH with volume and VWAP filters.",
                "CE"));
    }
}
