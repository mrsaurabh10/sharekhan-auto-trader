package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Calculates the pending ATR-source request from prior-day structure. Entry confirmation is enforced live. */
@Component
@RequiredArgsConstructor
public class AtrPreviousDayBreakoutQualificationService {

    static final int ATR_PERIOD = 75;

    private final StrategySupport support;

    public Fno925EntryQualificationService.Qualification qualify(ScriptMasterEntity spot,
                                                                   String optionType,
                                                                   LocalDateTime now) {
        List<StrategyCandle> fiveMinute = sorted(support.loadCandlesWithHistoricalFallback(spot, ATR_PERIOD + 1).candles());
        double atr = atr(fiveMinute);
        if (atr <= 0d) {
            return Fno925EntryQualificationService.Qualification.waiting("ATR(75) 5-minute data is unavailable");
        }
        LocalDate previousDay = previousTradingDay(fiveMinute, now.toLocalDate());
        if (previousDay == null) {
            return Fno925EntryQualificationService.Qualification.waiting("previous-day candles are unavailable");
        }
        List<StrategyCandle> previousDayCandles = fiveMinute.stream().filter(c -> previousDay.equals(c.date())).toList();
        if (previousDayCandles.isEmpty()) {
            return Fno925EntryQualificationService.Qualification.waiting("previous-day candles are unavailable");
        }

        boolean ce = "CE".equalsIgnoreCase(optionType);
        double pdh = previousDayCandles.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double pdl = previousDayCandles.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        // Prefer the most recent confirmed prior-day swing; fall back to the full-day high/low.
        // This makes "PDH or PD swing" an actionable alternative rather than always selecting PDH/PDL.
        double structuralLevel = ce
                ? latestSwingHigh(previousDayCandles).orElse(pdh)
                : latestSwingLow(previousDayCandles).orElse(pdl);
        double entry = structuralLevel + (ce ? 0.25d * atr : -0.25d * atr);

        double stop = ce ? entry - 2d * atr : entry + 2d * atr;
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), support.roundPrice(stop), pdh, pdl, null,
                ce ? "PENDING_PDH_SWING_HIGH_ATR_BREAKOUT" : "PENDING_PDL_SWING_LOW_ATR_BREAKDOWN"));
    }

    private List<StrategyCandle> sorted(List<StrategyCandle> candles) {
        return candles.stream().sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time)).toList();
    }

    private LocalDate previousTradingDay(List<StrategyCandle> candles, LocalDate today) {
        return candles.stream().map(StrategyCandle::date).filter(date -> date.isBefore(today)).max(Comparator.naturalOrder()).orElse(null);
    }

    private double atr(List<StrategyCandle> candles) {
        if (candles.size() < ATR_PERIOD + 1) return 0d;
        List<StrategyCandle> tail = candles.subList(candles.size() - ATR_PERIOD - 1, candles.size());
        double total = 0d;
        for (int i = 1; i < tail.size(); i++) {
            StrategyCandle prior = tail.get(i - 1);
            StrategyCandle current = tail.get(i);
            total += Math.max(current.high() - current.low(), Math.max(
                    Math.abs(current.high() - prior.close()), Math.abs(current.low() - prior.close())));
        }
        return total / ATR_PERIOD;
    }

    private Optional<Double> latestSwingHigh(List<StrategyCandle> candles) {
        Double latest = null;
        for (int i = 1; i < candles.size() - 1; i++) {
            if (candles.get(i).high() > candles.get(i - 1).high() && candles.get(i).high() > candles.get(i + 1).high()) {
                latest = candles.get(i).high();
            }
        }
        return Optional.ofNullable(latest);
    }

    private Optional<Double> latestSwingLow(List<StrategyCandle> candles) {
        Double latest = null;
        for (int i = 1; i < candles.size() - 1; i++) {
            if (candles.get(i).low() < candles.get(i - 1).low() && candles.get(i).low() < candles.get(i + 1).low()) {
                latest = candles.get(i).low();
            }
        }
        return Optional.ofNullable(latest);
    }
}
