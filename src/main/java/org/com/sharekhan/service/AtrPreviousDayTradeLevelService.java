package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Recalculates spot risk levels when a pending ATR prior-day request is manually repriced. */
@Component
public class AtrPreviousDayTradeLevelService {

    public static final String SOURCE = "atr-pdh-pdl-strategy";

    public boolean appliesTo(TriggerTradeRequestEntity request) {
        return request != null && SOURCE.equalsIgnoreCase(request.getSource());
    }

    /**
     * Preserves the originally calculated ATR from the request, then rebuilds the
     * 2-ATR stop and 2/3/4-ATR targets around a user-selected spot entry.
     */
    public Optional<Levels> recalculate(TriggerTradeRequestEntity request, double updatedEntry) {
        if (!appliesTo(request) || !valid(updatedEntry)) return Optional.empty();
        Double atr = originalAtr(request);
        if (atr == null || !valid(atr)) return Optional.empty();

        boolean ce = "CE".equalsIgnoreCase(request.getOptionType());
        double direction = ce ? 1d : -1d;
        return Optional.of(new Levels(
                round(updatedEntry - direction * 2d * atr),
                round(updatedEntry + direction * 2d * atr),
                round(updatedEntry + direction * 3d * atr),
                round(updatedEntry + direction * 4d * atr)));
    }

    private Double originalAtr(TriggerTradeRequestEntity request) {
        if (!valid(request.getEntryPrice())) return null;
        Double fromStop = divideDistance(request.getEntryPrice(), request.getStopLoss(), 2d);
        if (fromStop != null) return fromStop;
        Double fromT1 = divideDistance(request.getEntryPrice(), request.getTarget1(), 2d);
        if (fromT1 != null) return fromT1;
        Double fromT2 = divideDistance(request.getEntryPrice(), request.getTarget2(), 3d);
        if (fromT2 != null) return fromT2;
        return divideDistance(request.getEntryPrice(), request.getTarget3(), 4d);
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
