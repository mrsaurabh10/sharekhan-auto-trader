package org.com.sharekhan.util;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.strategy.SpotAtrPreviousDayBigTradePlusSellStrategy;

/** Entry direction is independent of option type: buying a put is still a BUY. */
public final class TradeSide {
    private TradeSide() { }
    public static boolean isShort(TriggeredTradeSetupEntity trade) {
        return trade != null && shortSide(trade.getEntryAction(), trade.getSource());
    }
    public static boolean isShort(TriggerTradeRequestEntity trade) {
        return trade != null && shortSide(trade.getEntryAction(), trade.getSource());
    }
    private static boolean shortSide(String action, String source) {
        return "SELL".equalsIgnoreCase(action) || (action == null && SpotAtrPreviousDayBigTradePlusSellStrategy.isSell(source));
    }
    public static String entry(TriggeredTradeSetupEntity trade) { return isShort(trade) ? "SELL" : "BUY"; }
    public static String exit(TriggeredTradeSetupEntity trade) { return isShort(trade) ? "BUY" : "SELL"; }
    public static int sign(TriggeredTradeSetupEntity trade) { return isShort(trade) ? -1 : 1; }
}
