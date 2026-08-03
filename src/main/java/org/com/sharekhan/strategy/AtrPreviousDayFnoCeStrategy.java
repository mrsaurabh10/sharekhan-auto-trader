package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

@Component
public class AtrPreviousDayFnoCeStrategy extends AbstractAtrPreviousDayFnoStrategy {
    public static final String TEMPLATE_ID = "FNO_ATR_PREVIOUS_DAY_CE";
    public AtrPreviousDayFnoCeStrategy(StrategySupport support, AtrPreviousDayBreakoutQualificationService qualificationService) {
        super(new StrategyMetadata(TEMPLATE_ID, "F&O ATR Previous-Day Breakout (CE)",
                "After 09:20, buys ATM CE on a one-minute close above the selected prior-day high or swing high plus 0.25 × ATR(75, 5-minute).", "CE"), support, qualificationService);
    }
}
