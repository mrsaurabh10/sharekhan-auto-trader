package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class Orb915930PeStrategy extends AbstractOrbStrategy {
    public Orb915930PeStrategy(StrategySupport support) {
        super(support, new StrategyMetadata(
                "ORB_915_930_PE",
                "ORB 9:15-9:30 PE",
                "First 15-minute range breakdown below ORL with volume and VWAP filters.",
                "PE"));
    }
}
