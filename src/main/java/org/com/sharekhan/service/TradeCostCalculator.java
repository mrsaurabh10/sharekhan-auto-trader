package org.com.sharekhan.service;

import org.com.sharekhan.util.TradeSide;

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
        if (trade == null || org.com.sharekhan.strategy.mcx.NaturalGasSession.owns(trade.getSource()) || trade.getQuantity() == null || trade.getQuantity() <= 0
                || trade.getExitPrice() == null || trade.getExitPrice() < 0) {
            return null;
        }

        Double entryPrice = trade.getActualEntryPrice() != null
                ? trade.getActualEntryPrice()
                : trade.getEntryPrice();
        if (entryPrice == null || entryPrice < 0) {
            return null;
        }

        return calculateCharges(trade, entryPrice, trade.getExitPrice(), trade.getPnl());
    }

    private static TradeCharges calculateCharges(TriggeredTradeSetupEntity trade,
                                                 double entryPrice,
                                                 double exitPrice,
                                                 Double grossPnlOverride) {
        if (trade == null || org.com.sharekhan.strategy.mcx.NaturalGasSession.owns(trade.getSource()) || trade.getQuantity() == null || trade.getQuantity() <= 0) {
            return null;
        }

        BigDecimal quantity = BigDecimal.valueOf(trade.getQuantity());
        BigDecimal buyPremium = BigDecimal.valueOf(TradeSide.isShort(trade) ? exitPrice : entryPrice).multiply(quantity);
        BigDecimal sellPremium = BigDecimal.valueOf(TradeSide.isShort(trade) ? entryPrice : exitPrice).multiply(quantity);
        BigDecimal turnover = buyPremium.add(sellPremium);

        boolean cash = isCashEquity(trade);
        BigDecimal brokerage = cash ? cashBrokerage(trade, entryPrice, exitPrice)
                : BROKERAGE_PER_LOT_PER_SIDE.multiply(BigDecimal.valueOf(resolveLots(trade))).multiply(BigDecimal.valueOf(2));
        boolean intradayCash = cash && isIntradayCash(trade);
        boolean exercised = !cash && isExercised(trade);
        BigDecimal stt = cash ? (intradayCash ? sellPremium.multiply(new BigDecimal("0.00025"))
                : turnover.multiply(new BigDecimal("0.001")))
                : sellPremium.multiply(exercised ? EXERCISED_STT_RATE : SELL_STT_RATE);
        // Cash reference tariff, including its published IPFT contribution in this component.
        BigDecimal exchangeRate = cash ? (isBse(trade) ? new BigDecimal("0.0000375")
                : new BigDecimal("0.000030700")) : EXCHANGE_TRANSACTION_RATE;
        BigDecimal exchangeTransactionCharges = turnover.multiply(exchangeRate);
        BigDecimal stampCharges = buyPremium.multiply(cash && !intradayCash ? new BigDecimal("0.00015") : STAMP_DUTY_RATE);
        BigDecimal sebiTransactionFees = turnover.multiply(SEBI_TRANSACTION_RATE);
        BigDecimal gst = brokerage.add(exchangeTransactionCharges)
                .add(sebiTransactionFees)
                .multiply(GST_RATE);
        BigDecimal total = brokerage.add(stt)
                .add(exchangeTransactionCharges)
                .add(stampCharges)
                .add(sebiTransactionFees)
                .add(gst);

        BigDecimal grossPnl = grossPnlOverride != null
                ? BigDecimal.valueOf(grossPnlOverride)
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

    /**
     * Returns the smallest tick-aligned option exit price that is net profitable after estimated
     * brokerage and statutory charges. A spot target must not close an option at break-even.
     */
    public static Double minimumProfitableExitPrice(TriggeredTradeSetupEntity trade, double tickSize) {
        if (trade == null || org.com.sharekhan.strategy.mcx.NaturalGasSession.owns(trade.getSource()) || TradeSide.isShort(trade) || trade.getQuantity() == null || trade.getQuantity() <= 0 || tickSize <= 0d || !Double.isFinite(tickSize)) {
            return null;
        }
        Double entryPrice = trade.getActualEntryPrice() != null ? trade.getActualEntryPrice() : trade.getEntryPrice();
        if (entryPrice == null || entryPrice <= 0d || !Double.isFinite(entryPrice)) {
            return null;
        }

        BigDecimal tick = BigDecimal.valueOf(tickSize);
        BigDecimal candidate = BigDecimal.valueOf(entryPrice).divide(tick, 0, RoundingMode.FLOOR)
                .add(BigDecimal.ONE).multiply(tick);
        for (int attempt = 0; attempt < 10_000; attempt++) {
            TradeCharges charges = calculateCharges(trade, entryPrice, candidate.doubleValue(), null);
            if (charges != null && charges.effectivePnl() > 0d) {
                return candidate.doubleValue();
            }
            candidate = candidate.add(tick);
        }
        return null;
    }

    /** Highest tick-aligned buy-back price with positive estimated net P&L for a short. */
    public static Double maximumProfitableCoverPrice(TriggeredTradeSetupEntity trade, double tickSize) {
        if (!TradeSide.isShort(trade) || org.com.sharekhan.strategy.mcx.NaturalGasSession.owns(trade.getSource()) || trade.getQuantity() == null || trade.getQuantity() <= 0
                || !Double.isFinite(tickSize) || tickSize <= 0) return null;
        Double entry = trade.getActualEntryPrice() != null ? trade.getActualEntryPrice() : trade.getEntryPrice();
        if (entry == null || !Double.isFinite(entry) || entry <= 0) return null;
        BigDecimal tick = BigDecimal.valueOf(tickSize);
        BigDecimal candidate = BigDecimal.valueOf(entry).divide(tick, 0, RoundingMode.CEILING).subtract(BigDecimal.ONE).multiply(tick);
        for (int attempt=0; attempt<10_000 && candidate.signum()>0; attempt++, candidate=candidate.subtract(tick)) {
            TradeCharges charges = calculateCharges(trade, entry, candidate.doubleValue(), null);
            if (charges != null && charges.effectivePnl()>0) return candidate.doubleValue();
        }
        return null;
    }

    public static boolean isCashEquity(TriggeredTradeSetupEntity trade) {
        if (trade == null || (trade.getOptionType() != null && !trade.getOptionType().isBlank())) return false;
        return java.util.Set.of("NC", "BC", "NSE", "BSE").contains(trade.getExchange() == null ? "" : trade.getExchange().toUpperCase(Locale.ROOT));
    }
    /** Non-managed display copy: never rewrite historical fills, gross P&L or stored fee estimates on a read. */
    public static TriggeredTradeSetupEntity cashCostView(TriggeredTradeSetupEntity trade) {
        if (!isCashEquity(trade)) return trade;
        var charges = calculateCharges(trade);
        if (charges == null) return trade;
        var copy = new TriggeredTradeSetupEntity();
        org.springframework.beans.BeanUtils.copyProperties(trade, copy);
        copy.setTradeCost(charges.totalTradeCost()); copy.setEffectivePnl(charges.effectivePnl());
        return copy;
    }
    public static String costModel(TriggeredTradeSetupEntity trade) {
        return isCashEquity(trade) ? "cash-order-reference-estimate-2026-10-26" : "legacy-option-lot-estimate";
    }
    private static boolean isIntradayCash(TriggeredTradeSetupEntity trade) {
        return TradeSide.isShort(trade) || Boolean.TRUE.equals(trade.getIntraday());
    }
    private static boolean isBse(TriggeredTradeSetupEntity trade) {
        return "BC".equalsIgnoreCase(trade.getExchange()) || "BSE".equalsIgnoreCase(trade.getExchange());
    }
    private static BigDecimal cashOrderBrokerage(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.0003")).min(new BigDecimal("20"));
    }
    private static BigDecimal cashBrokerage(TriggeredTradeSetupEntity trade, double entry, double exit) {
        if (!isIntradayCash(trade)) return BigDecimal.ZERO; // Delivery reference tariff; DP charges are not modeled.
        long shares = trade.getQuantity();
        // Staged legs share a single original entry order; each has its own exit.
        long entryShares = trade.getTargetOrderGroupId() != null && trade.getOriginalLots() != null
                ? Math.max(shares, trade.getOriginalLots()) : shares;
        BigDecimal entryCharge = cashOrderBrokerage(BigDecimal.valueOf(entry).multiply(BigDecimal.valueOf(entryShares)))
                .multiply(BigDecimal.valueOf(shares)).divide(BigDecimal.valueOf(entryShares), 12, RoundingMode.HALF_UP);
        return entryCharge.add(cashOrderBrokerage(BigDecimal.valueOf(exit).multiply(BigDecimal.valueOf(shares))));
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
