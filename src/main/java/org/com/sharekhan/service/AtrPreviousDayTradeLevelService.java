package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Recalculates spot risk levels when a pending ATR prior-day request is manually repriced. */
@Component
public class AtrPreviousDayTradeLevelService {

    public static final String SOURCE = "atr-pdh-pdl-strategy";

    public boolean appliesTo(TriggerTradeRequestEntity request) {
        return request != null && SOURCE.equalsIgnoreCase(request.getSource());
    }

    public boolean appliesTo(TriggeredTradeSetupEntity trade) {
        return trade != null && SOURCE.equalsIgnoreCase(trade.getSource());
    }

    /**
     * Preserves the originally calculated ATR from the request, then rebuilds the
     * 2-ATR stop and 3/5/6-ATR targets around a user-selected spot entry.
     */
    public Optional<Levels> recalculate(TriggerTradeRequestEntity request, double updatedEntry) {
        if (!appliesTo(request) || !valid(updatedEntry)) return Optional.empty();
        return recalculate(request.getOptionType(), request.getEntryPrice(), request.getStopLoss(),
                request.getTarget1(), request.getTarget2(), request.getTarget3(), updatedEntry);
    }

    /**
     * Executed setups retain the same spot-risk geometry as their originating
     * request. Editing the configured spot entry must therefore move all four
     * ATR levels together; actualEntryPrice remains the option fill price.
     */
    public Optional<Levels> recalculate(TriggeredTradeSetupEntity trade, double updatedEntry) {
        if (!appliesTo(trade) || !valid(updatedEntry)) return Optional.empty();
        return recalculate(trade.getOptionType(), trade.getEntryPrice(), trade.getStopLoss(),
                trade.getTarget1(), trade.getTarget2(), trade.getTarget3(), updatedEntry);
    }

    private Optional<Levels> recalculate(String optionType, Double originalEntry, Double stopLoss,
                                         Double target1, Double target2, Double target3, double updatedEntry) {
        Double atr = originalAtr(originalEntry, stopLoss, target1, target2, target3);
        if (atr == null || !valid(atr)) return Optional.empty();

        boolean ce = "CE".equalsIgnoreCase(optionType);
        double direction = ce ? 1d : -1d;
        return Optional.of(new Levels(
                round(updatedEntry - direction * 2d * atr),
                round(updatedEntry + direction * 3d * atr),
                round(updatedEntry + direction * 5d * atr),
                round(updatedEntry + direction * 6d * atr)));
    }

    private Double originalAtr(Double entry, Double stopLoss, Double target1, Double target2, Double target3) {
        if (!valid(entry)) return null;
        Double fromStop = divideDistance(entry, stopLoss, 2d);
        if (fromStop != null) return fromStop;
        Double fromT1 = divideDistance(entry, target1, 3d);
        if (fromT1 != null) return fromT1;
        Double fromT2 = divideDistance(entry, target2, 5d);
        if (fromT2 != null) return fromT2;
        return divideDistance(entry, target3, 6d);
    }

    private Double divideDistance(Double entry, Double level, double multiple) {
        if (!valid(level)) return null;
        double atr = Math.abs(level - entry) / multiple;
        return valid(atr) ? atr : null;
    }

    private boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value > 0d;
    }

    private boolean valid(double value) {
        return Double.isFinite(value) && value > 0d;
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    public record Levels(double stopLoss, double target1, double target2, double target3) { }
}
