package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class AtrPreviousDayFnoPeStrategy extends AbstractAtrPreviousDayFnoStrategy {
    public static final String TEMPLATE_ID = "FNO_ATR_PREVIOUS_DAY_PE";
    public AtrPreviousDayFnoPeStrategy(StrategySupport support, AtrPreviousDayBreakoutQualificationService qualificationService) {
        super(new StrategyMetadata(TEMPLATE_ID, "F&O ATR Previous-Day Breakdown (PE)",
                "After 09:20, buys ATM PE on a one-minute close below the selected prior-day low or swing low minus 0.25 × ATR(75, 5-minute).", "PE"), support, qualificationService);
    }
}
