package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Admission only: original spot brackets are never moved to manufacture reward/risk. */
public final class FnoMoverEntryRiskPolicy {
    public static final String SOURCE = "strategy:FNO_0925_MOVER_ATR_BREAKOUT";
    public static final double MIN_REWARD_RISK = 1.10;
    public static final Duration MAX_SPOT_AGE = Duration.ofSeconds(5);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private FnoMoverEntryRiskPolicy() { }

    public record Check(boolean applies, boolean allowed, String reason, Double spot, Double ratio) { }

    public static boolean owns(TriggeredTradeSetupEntity trade) {
        return trade != null && SOURCE.equals(trade.getSource());
    }

    public static Check evaluate(TriggeredTradeSetupEntity trade, Double spot,
                                 LocalDateTime observedAt, Instant now) {
        if (!owns(trade)) return new Check(false, true, null, null, null);
        if (!positive(spot) || observedAt == null || now == null) return unavailable(spot);
        Instant observed = observedAt.atZone(MARKET_ZONE).toInstant();
        if (observed.isAfter(now) || Duration.between(observed, now).compareTo(MAX_SPOT_AGE) > 0)
            return unavailable(spot);
        boolean ce = "CE".equalsIgnoreCase(trade.getOptionType());
        boolean pe = "PE".equalsIgnoreCase(trade.getOptionType());
        if ((!ce && !pe) || !positive(trade.getStopLoss()) || !positive(trade.getTarget1())
                || !spotReference(trade.getUseSpotForSl(), trade.getUseSpotPrice())
                || !spotReference(trade.getUseSpotForTarget(), trade.getUseSpotPrice()))
            return new Check(true, false, "FNO_ENTRY_RR_INVALID_BRACKET", spot, null);
        double risk = ce ? spot - trade.getStopLoss() : trade.getStopLoss() - spot;
        double reward = ce ? trade.getTarget1() - spot : spot - trade.getTarget1();
        if (!Double.isFinite(risk) || !Double.isFinite(reward) || risk <= 0 || reward <= 0)
            return new Check(true, false, "FNO_ENTRY_RR_INVALID_BRACKET", spot, null);
        double ratio = reward / risk;
        boolean allowed = Double.isFinite(ratio) && reward + 1e-9 >= MIN_REWARD_RISK * risk;
        return new Check(true, allowed, allowed ? null : "FNO_ENTRY_RR_BELOW_MINIMUM", spot, ratio);
    }

    private static Check unavailable(Double spot) {
        return new Check(true, false, "FNO_ENTRY_RR_SPOT_UNAVAILABLE", spot, null);
    }

    private static boolean spotReference(Boolean explicit, Boolean legacy) {
        return Boolean.TRUE.equals(explicit) || explicit == null && Boolean.TRUE.equals(legacy);
    }

    private static boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }
}
