package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

/** Cash short entries use a distinct persisted source so direction survives restart. */
@Component
public class SpotAtrPreviousDayBigTradePlusSellStrategy extends SpotAtrPreviousDayBigTradePlusStrategy {
    public static final String SELL_SOURCE = "spot-atr-pdl-bigtradeplus";
    public static final String SELL_TEMPLATE_ID = "SPOT_ATR_PDL_BIGTRADEPLUS";
    public SpotAtrPreviousDayBigTradePlusSellStrategy(StrategySupport support,
            AtrPreviousDayBreakoutQualificationService qualification) { super(support, qualification); }
    @Override protected boolean sellSide() { return true; }
    @Override protected String source() { return SELL_SOURCE; }
    @Override protected String templateId() { return SELL_TEMPLATE_ID; }
    public static boolean isSell(String source) { return SELL_SOURCE.equalsIgnoreCase(source); }
}
