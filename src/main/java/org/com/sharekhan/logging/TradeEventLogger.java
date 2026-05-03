package org.com.sharekhan.logging;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.StringJoiner;

/**
 * Centralised trade logging helper so that operationally relevant events
 * (trigger claims, order life-cycle, SL actions) are consistently captured
 * with rich metadata.
 */
@Slf4j
@UtilityClass
public class TradeEventLogger {

    public static void logEntryTriggered(TriggerTradeRequestEntity trigger,
                                         double referenceLtp,
                                         String triggerSource,
                                         String conditionSummary) {
        log.info("ENTRY_TRIGGER | {} | refLtp={} | source={} | condition={}",
                describeTrigger(trigger),
                formatPrice(referenceLtp),
                safe(triggerSource),
                safe(conditionSummary));
    }

    public static void logGapRejection(TriggerTradeRequestEntity trigger,
                                       String gapLabel,
                                       double gapPrice,
                                       double entryPrice,
                                       double tolerancePercent) {
        log.warn("ENTRY_GAP_REJECT | {} | {}={} | entryPrice={} | tolerance={}%",
                describeTrigger(trigger),
                safe(gapLabel),
                formatPrice(gapPrice),
                formatPrice(entryPrice),
                formatPercent(tolerancePercent));
    }

    public static void logOrderAccepted(String stage,
                                        TriggerTradeRequestEntity trigger,
                                        OrderPlacementResult result,
                                        Double referencePrice) {
        log.info("{}_ORDER_ACCEPTED | {} | orderId={} | brokerStatus={} | attemptedPrice={} | referencePrice={}",
                safe(stage),
                describeTrigger(trigger),
                safe(result != null ? result.getOrderId() : null),
                safe(result != null ? result.getStatus() : null),
                formatPrice(resolveAttemptedPrice(result, referencePrice)),
                formatPrice(referencePrice));
    }

    public static void logOrderRejected(String stage,
                                        TriggerTradeRequestEntity trigger,
                                        String reason,
                                        Double referencePrice) {
        log.error("{}_ORDER_REJECTED | {} | attemptedPrice={} | referencePrice={} | reason={}",
                safe(stage),
                describeTrigger(trigger),
                formatPrice(referencePrice),
                formatPrice(referencePrice),
                safe(reason));
    }

    public static void logOrderAccepted(String stage,
                                        TriggeredTradeSetupEntity trade,
                                        OrderPlacementResult result,
                                        Double referencePrice) {
        log.info("{}_ORDER_ACCEPTED | {} | orderId={} | brokerStatus={} | attemptedPrice={} | referencePrice={}",
                safe(stage),
                describeTrade(trade),
                safe(result != null ? result.getOrderId() : null),
                safe(result != null ? result.getStatus() : null),
                formatPrice(resolveAttemptedPrice(result, referencePrice)),
                formatPrice(referencePrice));
    }

    public static void logOrderRejected(String stage,
                                        TriggeredTradeSetupEntity trade,
                                        String reason,
                                        Double referencePrice) {
        logOrderRejected(stage, trade, reason, referencePrice, referencePrice);
    }

    public static void logOrderRejected(String stage,
                                        TriggeredTradeSetupEntity trade,
                                        String reason,
                                        Double referencePrice,
                                        Double attemptedPrice) {
        log.error("{}_ORDER_REJECTED | {} | attemptedPrice={} | referencePrice={} | reason={}",
                safe(stage),
                describeTrade(trade),
                formatPrice(attemptedPrice),
                formatPrice(referencePrice),
                safe(reason));
    }

    public static void logOrderAttempt(String stage,
                                       TriggeredTradeSetupEntity trade,
                                       int attemptNumber,
                                       String action,
                                       Double attemptedPrice,
                                       String orderId) {
        log.info("{}_ORDER_ATTEMPT | {} | attempt={} | action={} | attemptedPrice={} | orderId={}",
                safe(stage),
                describeTrade(trade),
                attemptNumber,
                safe(action),
                formatPrice(attemptedPrice),
                safe(orderId));
    }

    public static void logOrderExecuted(String stage,
                                        TriggeredTradeSetupEntity trade,
                                        Double executedPrice,
                                        String status) {
        log.info("{}_ORDER_EXECUTED | {} | executedPrice={} | status={}",
                safe(stage),
                describeTrade(trade),
                formatPrice(executedPrice),
                safe(status));
    }

    public static void logStopLossTriggered(TriggeredTradeSetupEntity trade,
                                            Double triggerPrice,
                                            Double tradedLtp,
                                            Double spotLtp) {
        log.warn("STOP_LOSS_TRIGGERED | {} | triggerPrice={} | tradedLtp={} | spotLtp={}",
                describeTrade(trade),
                formatPrice(triggerPrice),
                formatPrice(tradedLtp),
                formatPrice(spotLtp));
    }

    private static String describeTrigger(TriggerTradeRequestEntity trigger) {
        if (trigger == null) {
            return "triggerId=NA";
        }
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add("triggerId=" + safe(trigger.getId()));
        joiner.add("symbol=" + safe(trigger.getSymbol()));
        joiner.add("strike=" + formatPrice(trigger.getStrikePrice()));
        joiner.add("optionType=" + safe(trigger.getOptionType()));
        joiner.add("expiry=" + safe(trigger.getExpiry()));
        joiner.add("lots=" + safe(trigger.getLots()));
        joiner.add("quantity=" + safe(trigger.getQuantity()));
        joiner.add("spotScrip=" + safe(trigger.getSpotScripCode()));
        joiner.add("useSpotEntry=" + safe(trigger.getUseSpotForEntry()));
        joiner.add("userId=" + safe(trigger.getAppUserId()));
        return joiner.toString();
    }

    private static String describeTrade(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return "tradeId=NA";
        }
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add("tradeId=" + safe(trade.getId()));
        joiner.add("symbol=" + safe(trade.getSymbol()));
        joiner.add("strike=" + formatPrice(trade.getStrikePrice()));
        joiner.add("optionType=" + safe(trade.getOptionType()));
        joiner.add("expiry=" + safe(trade.getExpiry()));
        joiner.add("lots=" + safe(trade.getLots()));
        joiner.add("quantity=" + safe(trade.getQuantity()));
        joiner.add("orderId=" + safe(trade.getOrderId()));
        joiner.add("spotScrip=" + safe(trade.getSpotScripCode()));
        joiner.add("status=" + safe(trade.getStatus()));
        joiner.add("userId=" + safe(trade.getAppUserId()));
        return joiner.toString();
    }

    private static String safe(Object value) {
        return value == null ? "NA" : String.valueOf(value);
    }

    private static String formatPrice(Double value) {
        if (value == null) {
            return "NA";
        }
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static Double resolveAttemptedPrice(OrderPlacementResult result, Double fallbackPrice) {
        return result != null && result.getAttemptedPrice() != null
                ? result.getAttemptedPrice()
                : fallbackPrice;
    }

    private static String formatPrice(double value) {
        return formatPrice(Double.valueOf(value));
    }

    private static String formatPercent(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
