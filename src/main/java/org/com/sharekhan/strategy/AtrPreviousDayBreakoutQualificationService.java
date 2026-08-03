package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.UserConfigService;
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
    static final String ENTRY_OFFSET_ATR_CONFIG = "fno_atr_previous_day_entry_offset_atr";
    private static final double DEFAULT_ENTRY_OFFSET_ATR = 0.25d;

    private final StrategySupport support;
    private final MStockHistoricalService mStockHistoricalService;
    private final UserConfigService userConfigService;

    public Fno925EntryQualificationService.Qualification qualify(ScriptMasterEntity spot,
                                                                   String optionType,
                                                                   Long appUserId,
                                                                   LocalDateTime now) {
        List<StrategyCandle> fiveMinute = loadMStockFiveMinuteChart(spot, now);
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
        double entryOffset = entryOffsetAtr(appUserId) * atr;
        double entry = structuralLevel + (ce ? entryOffset : -entryOffset);

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

    private double entryOffsetAtr(Long appUserId) {
        try {
            String configured = userConfigService.getConfig(appUserId, ENTRY_OFFSET_ATR_CONFIG, null);
            if (configured == null || configured.isBlank()) {
                return DEFAULT_ENTRY_OFFSET_ATR;
            }
            double value = Double.parseDouble(configured.trim());
            return Double.isFinite(value) && value >= 0d ? value : DEFAULT_ENTRY_OFFSET_ATR;
        } catch (Exception ignored) {
            return DEFAULT_ENTRY_OFFSET_ATR;
        }
    }

    /** MStock's historical endpoint supplies both today's candles and the required prior-day chart context. */
    private List<StrategyCandle> loadMStockFiveMinuteChart(ScriptMasterEntity spot, LocalDateTime now) {
        try {
            LocalDateTime from = now.minusDays(15).withHour(9).withMinute(15).withSecond(0).withNano(0);
            MStockHistoricalService.HistoricalResponse response = mStockHistoricalService.getHistoricalCandles(
                    spot.getScripCode(), null, null, null, null, null,
                    "5minute", from.toLocalDate().toString(), now.toLocalDate().toString());
            if (response == null || response.candles() == null) return List.of();
            return sorted(response.candles().stream()
                    .filter(c -> c != null && c.date() != null && c.time() != null)
                    .filter(c -> c.high() > 0d && c.low() > 0d && c.close() > 0d)
                    .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList());
        } catch (Exception e) {
            throw new IllegalStateException("MStock 5-minute chart is unavailable for "
                    + (spot != null ? spot.getTradingSymbol() : "selected symbol") + ": " + e.getMessage(), e);
        }
    }
}
