package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class TradeCostCalculator {

    private static final BigDecimal BROKERAGE_PER_LOT_PER_SIDE = new BigDecimal("5");
    private static final BigDecimal SELL_STT_RATE = new BigDecimal("0.0010");
    private static final BigDecimal EXERCISED_STT_RATE = new BigDecimal("0.00125");
    private static final BigDecimal EXCHANGE_TRANSACTION_RATE = new BigDecimal("0.0003503");
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.00003");
    // SEBI fee is Rs 10 per crore of turnover (0.0001%).
    private static final BigDecimal SEBI_TRANSACTION_RATE = new BigDecimal("0.000001");
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");

    public TradeCharges calculate(TriggeredTradeSetupEntity trade) {
        return calculateCharges(trade);
    }

    public static TradeCharges calculateCharges(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getQuantity() == null || trade.getQuantity() <= 0
                || trade.getExitPrice() == null || trade.getExitPrice() < 0) {
            return null;
        }

        Double entryPrice = trade.getActualEntryPrice() != null
                ? trade.getActualEntryPrice()
                : trade.getEntryPrice();
        if (entryPrice == null || entryPrice < 0) {
            return null;
        }

        BigDecimal quantity = BigDecimal.valueOf(trade.getQuantity());
        BigDecimal buyPremium = BigDecimal.valueOf(entryPrice).multiply(quantity);
        BigDecimal sellPremium = BigDecimal.valueOf(trade.getExitPrice()).multiply(quantity);
        BigDecimal turnover = buyPremium.add(sellPremium);

        int lots = resolveLots(trade);
        BigDecimal brokerage = BROKERAGE_PER_LOT_PER_SIDE
                .multiply(BigDecimal.valueOf(lots))
                .multiply(BigDecimal.valueOf(2));
        boolean exercised = isExercised(trade);
        BigDecimal stt = sellPremium.multiply(exercised ? EXERCISED_STT_RATE : SELL_STT_RATE);
        BigDecimal exchangeTransactionCharges = turnover.multiply(EXCHANGE_TRANSACTION_RATE);
        BigDecimal stampCharges = buyPremium.multiply(STAMP_DUTY_RATE);
        BigDecimal sebiTransactionFees = turnover.multiply(SEBI_TRANSACTION_RATE);
        BigDecimal gst = brokerage.add(exchangeTransactionCharges)
                .add(sebiTransactionFees)
                .multiply(GST_RATE);
        BigDecimal total = brokerage.add(stt)
                .add(exchangeTransactionCharges)
                .add(stampCharges)
                .add(sebiTransactionFees)
                .add(gst);

        BigDecimal grossPnl = trade.getPnl() != null
                ? BigDecimal.valueOf(trade.getPnl())
                : sellPremium.subtract(buyPremium);
        BigDecimal effectivePnl = grossPnl.subtract(total);

        return new TradeCharges(
                money(brokerage),
                money(stt),
                money(exchangeTransactionCharges),
                money(stampCharges),
                money(sebiTransactionFees),
                money(gst),
                money(total),
                money(effectivePnl),
                exercised
        );
    }

    private static int resolveLots(TriggeredTradeSetupEntity trade) {
        if (trade.getLots() != null && trade.getLots() > 0) {
            return trade.getLots();
        }
        if (trade.getOriginalLots() != null && trade.getOriginalLots() > 0) {
            return trade.getOriginalLots();
        }
        // Older rows may predate the lots column; they still represent at least one order lot.
        return 1;
    }

    private static boolean isExercised(TriggeredTradeSetupEntity trade) {
        String reason = trade.getExitReason();
        return reason != null && reason.toUpperCase(Locale.ROOT).contains("EXERCIS");
    }

    private static double money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record TradeCharges(
            double brokerage,
            double stt,
            double exchangeTransactionCharges,
            double stampCharges,
            double sebiTransactionFees,
            double gst,
            double totalTradeCost,
            double effectivePnl,
            boolean exercised
    ) {
    }
}
