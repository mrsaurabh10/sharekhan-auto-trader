package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class SupertrendRsiEmaAdxPeStrategy extends AbstractSupertrendRsiEmaAdxStrategy {
    public SupertrendRsiEmaAdxPeStrategy(StrategySupport support, IndicatorService indicatorService,
                                         SupertrendSignalRules rules, SupertrendDecisionDiagnostics diagnostics) {
        super(support, indicatorService, new StrategyMetadata(
                "ST_RSI_EMA_ADX_PE",
                "Supertrend RSI EMA ADX PE",
                "5-minute PE entry when Supertrend, RSI, 50 EMA, ADX/DI, and candle structure align. TSL enabled for more than one lot.",
                "PE"), rules, diagnostics);
    }
}
