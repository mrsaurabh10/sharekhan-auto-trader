package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.UserConfigService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Calculates the pending ATR-source request from prior-day structure. Entry confirmation is enforced live. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AtrPreviousDayBreakoutQualificationService {

    static final int ATR_PERIOD = 75;
    static final String ENTRY_OFFSET_ATR_CONFIG = "fno_atr_previous_day_entry_offset_atr";
    private static final double DEFAULT_ENTRY_OFFSET_ATR = 0.25d;
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

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
        LocalDate referenceDay = referenceDay(fiveMinute, now);
        if (referenceDay == null) {
            return Fno925EntryQualificationService.Qualification.waiting("previous-day candles are unavailable");
        }
        List<StrategyCandle> previousDayCandles = fiveMinute.stream().filter(c -> referenceDay.equals(c.date())).toList();
        if (previousDayCandles.isEmpty()) {
            return Fno925EntryQualificationService.Qualification.waiting("previous-day candles are unavailable");
        }

        boolean ce = "CE".equalsIgnoreCase(optionType);
        double pdh = previousDayCandles.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double pdl = previousDayCandles.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        Optional<Double> swingHigh = latestSwingHigh(previousDayCandles);
        Optional<Double> swingLow = latestSwingLow(previousDayCandles);
        // Prefer the most recent confirmed prior-day swing; fall back to the full-day high/low.
        // This makes "PDH or PD swing" an actionable alternative rather than always selecting PDH/PDL.
        double structuralLevel = ce
                ? swingHigh.orElse(pdh)
                : swingLow.orElse(pdl);
        double offsetMultiplier = entryOffsetAtr(appUserId);
        double entryOffset = offsetMultiplier * atr;
        double entry = structuralLevel + (ce ? entryOffset : -entryOffset);
        String selectedLevelType = ce
                ? (swingHigh.isPresent() ? "PD_SWING_HIGH" : "PDH")
                : (swingLow.isPresent() ? "PD_SWING_LOW" : "PDL");
        log.info("ATR_PREVIOUS_DAY_LEVELS | symbol={} | referenceDate={} | optionType={} | pdh={} | pdl={} | "
                        + "swingHigh={} | swingLow={} | atr75_5m={} | offsetAtr={} | selectedType={} | selectedLevel={} | entry={}",
                spot.getTradingSymbol(), referenceDay, optionType, support.roundPrice(pdh), support.roundPrice(pdl),
                swingHigh.map(support::roundPrice).orElse(null), swingLow.map(support::roundPrice).orElse(null),
                support.roundPrice(atr), offsetMultiplier, selectedLevelType, support.roundPrice(structuralLevel),
                support.roundPrice(entry));

        double stop = ce ? entry - 2d * atr : entry + 2d * atr;
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), support.roundPrice(stop), pdh, pdl, null,
                ce ? "PENDING_PDH_SWING_HIGH_ATR_BREAKOUT" : "PENDING_PDL_SWING_LOW_ATR_BREAKDOWN"));
    }

    private List<StrategyCandle> sorted(List<StrategyCandle> candles) {
        return candles.stream().sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time)).toList();
    }

    /** After close, today's completed session is the next trading day's previous-day reference. */
    private LocalDate referenceDay(List<StrategyCandle> candles, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (!now.toLocalTime().isBefore(MARKET_CLOSE)
                && candles.stream().anyMatch(candle -> today.equals(candle.date()))) {
            return today;
        }
        return candles.stream().map(StrategyCandle::date)
                .filter(date -> date.isBefore(today)).max(Comparator.naturalOrder()).orElse(null);
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

    /**
     * The MStock intraday chart is authoritative for today's five-minute candles and pivots.
     * MStock historical data only supplies earlier candles needed for ATR's previous-close context.
     */
    private List<StrategyCandle> loadMStockFiveMinuteChart(ScriptMasterEntity spot, LocalDateTime now) {
        try {
            List<StrategyCandle> intraday = sorted(support.loadCandles(spot, "5minute").candles());
            if (intraday.isEmpty()) {
                throw new IllegalStateException("MStock intraday chart returned no five-minute candles");
            }
            LocalDateTime from = now.minusDays(15).withHour(9).withMinute(15).withSecond(0).withNano(0);
            MStockHistoricalService.HistoricalResponse response = mStockHistoricalService.getHistoricalCandles(
                    spot.getScripCode(), null, null, null, null, null,
                    "5minute", from.toLocalDate().toString(), now.toLocalDate().toString());
            Map<String, StrategyCandle> merged = new TreeMap<>();
            if (response != null && response.candles() != null) {
                response.candles().stream()
                        .filter(c -> c != null && c.date() != null && c.time() != null)
                        .filter(c -> c.high() > 0d && c.low() > 0d && c.close() > 0d)
                        .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                        .forEach(candle -> merged.put(candle.date() + "T" + candle.time(), candle));
            }
            // Put current-day intraday candles last so their highs/lows determine PDH and swing structure.
            intraday.forEach(candle -> merged.put(candle.date() + "T" + candle.time(), candle));
            return sorted(List.copyOf(merged.values()));
        } catch (Exception e) {
            throw new IllegalStateException("MStock 5-minute chart is unavailable for "
                    + (spot != null ? spot.getTradingSymbol() : "selected symbol") + ": " + e.getMessage(), e);
        }
    }
}
