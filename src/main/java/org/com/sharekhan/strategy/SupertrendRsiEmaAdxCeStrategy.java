package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class SupertrendRsiEmaAdxCeStrategy extends AbstractSupertrendRsiEmaAdxStrategy {
    public SupertrendRsiEmaAdxCeStrategy(StrategySupport support, IndicatorService indicatorService,
                                         SupertrendSignalRules rules, SupertrendDecisionDiagnostics diagnostics) {
        super(support, indicatorService, new StrategyMetadata(
                "ST_RSI_EMA_ADX_CE",
                "Supertrend RSI EMA ADX CE",
                "5-minute CE entry when Supertrend, RSI, 50 EMA, ADX/DI, and candle structure align. TSL enabled for more than one lot.",
                "CE"), rules, diagnostics);
    }
}
