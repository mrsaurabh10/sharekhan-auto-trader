package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
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
    static final String MIN_ENTRY_DISTANCE_ATR_CONFIG = "fno_atr_previous_day_min_entry_distance_atr";
    private static final double DEFAULT_ENTRY_OFFSET_ATR = 0.25d;
    /** Avoid minor pivots and untradeable, distant prior-day levels. */
    private static final double MIN_SWING_REVERSAL_ATR = 0.75d;
    /** Minimum close-to-entry breakout buffer when PDH/PDL is at the close. */
    private static final double DEFAULT_MIN_ENTRY_DISTANCE_ATR = 0.35d;
    private static final double MAX_ENTRY_DISTANCE_ATR = 3d;
    private static final double REENTRY_RESET_ATR = 0.5d;
    private static final double REENTRY_BREAKOUT_ATR = 0.25d;
    private static final double ENTRY_DISTANCE_COMPARISON_EPSILON = 1e-9d;
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
        // The completed final MStock candle is authoritative, including a volatile closing auction.
        double referenceClose = previousDayCandles.get(previousDayCandles.size() - 1).close();
        double offsetMultiplier = entryOffsetAtr(appUserId);
        double entryOffset = offsetMultiplier * atr;
        double minimumDistanceAtr = minimumEntryDistanceAtr(appUserId);
        List<SwingPoint> swingHighs = withReferenceLevel(meaningfulSwingHighs(previousDayCandles, atr), pdh);
        List<SwingPoint> swingLows = withReferenceLevel(meaningfulSwingLows(previousDayCandles, atr), pdl);
        Optional<SwingPoint> selectedSwing = selectTradableSwing(
                ce ? swingHighs : swingLows, referenceClose, entryOffset, minimumDistanceAtr, atr, ce);
        List<String> swingAssessments = assessSwingEntries(
                ce ? swingHighs : swingLows, referenceClose, entryOffset, minimumDistanceAtr, atr, ce);
        String selectedLevelType = selectedSwing.map(swing -> swing.referenceLevel()
                ? (ce ? "PDH" : "PDL")
                : (ce ? "PD_MEANINGFUL_SWING_HIGH" : "PD_MEANINGFUL_SWING_LOW"))
                .orElse(ce ? "PD_MEANINGFUL_SWING_HIGH" : "PD_MEANINGFUL_SWING_LOW");
        log.info("ATR_PREVIOUS_DAY_LEVELS | symbol={} | referenceDate={} | optionType={} | pdh={} | pdl={} | "
                        + "referenceClose={} | referenceCloseSource={} | swingHighCandidates={} | swingLowCandidates={} | atr75_5m={} | "
                        + "offsetAtr={} | minEntryDistanceAtr={} | minReversalAtr={} | entryDistanceAtrRange={}-{} | swingEntryAssessments={} | "
                        + "selectedType={} | selectedLevel={} | entry={}",
                spot.getTradingSymbol(), referenceDay, optionType, support.roundPrice(pdh), support.roundPrice(pdl),
                support.roundPrice(referenceClose), "FINAL_MSTOCK_CLOSE", swingHighs, swingLows, support.roundPrice(atr), offsetMultiplier,
                minimumDistanceAtr, MIN_SWING_REVERSAL_ATR, minimumDistanceAtr, MAX_ENTRY_DISTANCE_ATR, swingAssessments, selectedLevelType,
                selectedSwing.map(SwingPoint::price).map(support::roundPrice).orElse(null),
                selectedSwing.map(swing -> support.roundPrice(entryFor(
                        swing.price(), referenceClose, entryOffset, minimumDistanceAtr, atr, ce))).orElse(null));

        if (selectedSwing.isEmpty()) {
            return Fno925EntryQualificationService.Qualification.waiting(
                    "no meaningful prior-day " + (ce ? "swing high" : "swing low")
                            + " has an entry between " + minimumDistanceAtr + " and "
                            + MAX_ENTRY_DISTANCE_ATR + " ATR from the prior-day close");
        }

        double structuralLevel = selectedSwing.get().price();
        double entry = support.roundPrice(entryFor(
                structuralLevel, referenceClose, entryOffset, minimumDistanceAtr, atr, ce));
        // This is intentionally checked again after price rounding. A PE must
        // never be configured above/equal to the reference close (and a CE
        // never below/equal), irrespective of source quirks or tick rounding.
        if (ce ? entry <= referenceClose : entry >= referenceClose) {
            return Fno925EntryQualificationService.Qualification.waiting(
                    "rounded prior-day " + (ce ? "CE" : "PE") + " entry is not beyond the prior-session close");
        }

        double stop = ce ? entry - 2d * atr : entry + 2d * atr;
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), support.roundPrice(stop), support.roundPrice(atr), pdh, pdl, null,
                ce ? "PENDING_MEANINGFUL_SWING_HIGH_ATR_BREAKOUT" : "PENDING_MEANINGFUL_SWING_LOW_ATR_BREAKDOWN"));
    }

    /**
     * A re-entry is not a second touch of yesterday's level.  It requires price
     * to reset through the old entry, form a new five-minute reversal, then
     * break that new structure by a meaningful buffer.
     */
    public Fno925EntryQualificationService.Qualification qualifyReentry(ScriptMasterEntity spot,
                                                                          String optionType,
                                                                          TriggeredTradeSetupEntity priorTrade,
                                                                          LocalDateTime now) {
        if (priorTrade == null || priorTrade.getExitedAt() == null || priorTrade.getEntryPrice() == null) {
            return Fno925EntryQualificationService.Qualification.waiting("prior ATR entry is not terminal");
        }
        List<StrategyCandle> candles = loadMStockFiveMinuteChart(spot, now);
        double atr = atr(candles);
        if (atr <= 0d) return Fno925EntryQualificationService.Qualification.waiting("ATR(75) 5-minute data is unavailable");
        boolean ce = "CE".equalsIgnoreCase(optionType);
        List<StrategyCandle> afterExit = candles.stream()
                .filter(c -> c.date().equals(now.toLocalDate()))
                .filter(c -> LocalDateTime.of(c.date(), c.time()).isAfter(priorTrade.getExitedAt()))
                .filter(c -> LocalDateTime.of(c.date(), c.time()).isBefore(now.withSecond(0).withNano(0)))
                .toList();
        if (afterExit.size() < 3) return Fno925EntryQualificationService.Qualification.waiting("waiting for post-exit five-minute structure");

        double oldEntry = priorTrade.getEntryPrice();
        int resetIndex = -1;
        for (int i = 0; i < afterExit.size(); i++) {
            StrategyCandle candle = afterExit.get(i);
            boolean reset = ce ? candle.low() <= oldEntry - REENTRY_RESET_ATR * atr
                    : candle.high() >= oldEntry + REENTRY_RESET_ATR * atr;
            if (reset) { resetIndex = i; break; }
        }
        if (resetIndex < 0 || afterExit.size() - resetIndex < 3) {
            return Fno925EntryQualificationService.Qualification.waiting("waiting for a 0.5-ATR reset through the original entry");
        }

        // Keep the reset, reversal and breakout as distinct five-minute events.
        // Otherwise a single volatile candle can satisfy all three predicates and
        // recreate the exact stale-level re-entry this guard is intended to stop.
        List<StrategyCandle> structure = afterExit.subList(resetIndex, afterExit.size());
        StrategyCandle latest = structure.get(structure.size() - 1);
        List<StrategyCandle> reversalStructure = structure.subList(1, structure.size() - 1);
        if (reversalStructure.isEmpty()) {
            return Fno925EntryQualificationService.Qualification.waiting("waiting for a post-reset five-minute reversal");
        }
        double resetExtreme = ce ? structure.get(0).low() : structure.get(0).high();
        boolean reversal = ce
                ? reversalStructure.stream().anyMatch(c -> c.close() >= resetExtreme + REENTRY_BREAKOUT_ATR * atr)
                : reversalStructure.stream().anyMatch(c -> c.close() <= resetExtreme - REENTRY_BREAKOUT_ATR * atr);
        double structuralBreak = ce
                ? reversalStructure.stream().mapToDouble(StrategyCandle::high).max().orElse(Double.NaN)
                : reversalStructure.stream().mapToDouble(StrategyCandle::low).min().orElse(Double.NaN);
        double entry = ce ? structuralBreak + REENTRY_BREAKOUT_ATR * atr : structuralBreak - REENTRY_BREAKOUT_ATR * atr;
        boolean broke = ce ? latest.close() >= entry : latest.close() <= entry;
        if (!reversal || !broke) {
            return Fno925EntryQualificationService.Qualification.waiting("waiting for fresh five-minute reversal and structural breakout");
        }
        double stop = ce ? entry - 2d * atr : entry + 2d * atr;
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), support.roundPrice(stop), support.roundPrice(atr), 0d, 0d, null,
                ce ? "PENDING_FRESH_REENTRY_CE" : "PENDING_FRESH_REENTRY_PE"));
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

    private List<SwingPoint> meaningfulSwingHighs(List<StrategyCandle> candles, double atr) {
        java.util.ArrayList<SwingPoint> swings = new java.util.ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            if (candles.get(i).high() > candles.get(i - 1).high() && candles.get(i).high() > candles.get(i + 1).high()) {
                double pullback = candles.get(i).high() - candles.subList(i + 1, candles.size()).stream()
                        .mapToDouble(StrategyCandle::low).min().orElse(candles.get(i).low());
                if (pullback >= MIN_SWING_REVERSAL_ATR * atr) {
                    swings.add(new SwingPoint(candles.get(i).time(), support.roundPrice(candles.get(i).high()), false));
                }
            }
        }
        return List.copyOf(swings);
    }

    private List<SwingPoint> meaningfulSwingLows(List<StrategyCandle> candles, double atr) {
        java.util.ArrayList<SwingPoint> swings = new java.util.ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            if (candles.get(i).low() < candles.get(i - 1).low() && candles.get(i).low() < candles.get(i + 1).low()) {
                double bounce = candles.subList(i + 1, candles.size()).stream()
                        .mapToDouble(StrategyCandle::high).max().orElse(candles.get(i).high()) - candles.get(i).low();
                if (bounce >= MIN_SWING_REVERSAL_ATR * atr) {
                    swings.add(new SwingPoint(candles.get(i).time(), support.roundPrice(candles.get(i).low()), false));
                }
            }
        }
        return List.copyOf(swings);
    }

    private Optional<SwingPoint> selectTradableSwing(List<SwingPoint> candidates, double referenceClose,
                                                       double entryOffset, double minimumDistanceAtr, double atr, boolean ce) {
        return candidates.stream()
                .filter(swing -> {
                    // A breakout must originate at/above the close for CE and
                    // at/below it for PE. The buffer is for close-at-PDH/PDL,
                    // not a way to turn a structurally wrong-side swing into a trigger.
                    if (ce ? swing.price() < referenceClose : swing.price() > referenceClose) {
                        return false;
                    }
                    double entry = entryFor(swing.price(), referenceClose, entryOffset, minimumDistanceAtr, atr, ce);
                    double distance = ce ? entry - referenceClose : referenceClose - entry;
                    return (ce ? entry > referenceClose : entry < referenceClose)
                            // The buffer itself can create an exact 0.35-ATR
                            // distance. Allow a tiny IEEE-754 tolerance so a
                            // value such as 0.18199999999996 is not rejected
                            // against the mathematically identical 0.182.
                            && distance + ENTRY_DISTANCE_COMPARISON_EPSILON >= minimumDistanceAtr * atr
                            && distance <= MAX_ENTRY_DISTANCE_ATR * atr + ENTRY_DISTANCE_COMPARISON_EPSILON;
                })
                .min(Comparator.comparingDouble(swing -> Math.abs(entryFor(
                        swing.price(), referenceClose, entryOffset, minimumDistanceAtr, atr, ce) - referenceClose)));
    }

    private List<String> assessSwingEntries(List<SwingPoint> candidates, double referenceClose,
                                            double entryOffset, double minimumDistanceAtr, double atr, boolean ce) {
        return candidates.stream().map(swing -> {
            boolean structuralSideValid = ce ? swing.price() >= referenceClose : swing.price() <= referenceClose;
            double entry = entryFor(swing.price(), referenceClose, entryOffset, minimumDistanceAtr, atr, ce);
            double distanceAtr = (ce ? entry - referenceClose : referenceClose - entry) / atr;
            boolean accepted = structuralSideValid
                    && distanceAtr + ENTRY_DISTANCE_COMPARISON_EPSILON >= minimumDistanceAtr
                    && distanceAtr <= MAX_ENTRY_DISTANCE_ATR + ENTRY_DISTANCE_COMPARISON_EPSILON;
            return swing.time() + "@" + support.roundPrice(swing.price()) + " entry=" + support.roundPrice(entry)
                    + " distanceAtr=" + support.roundPrice(distanceAtr) + " structuralSideValid=" + structuralSideValid
                    + " accepted=" + accepted;
        }).toList();
    }

    /** PDH/PDL are valid structural levels too, but must pass the same entry-distance guard as a swing. */
    private List<SwingPoint> withReferenceLevel(List<SwingPoint> swings, double level) {
        if (swings.stream().anyMatch(swing -> Double.compare(swing.price(), support.roundPrice(level)) == 0)) {
            return swings;
        }
        java.util.ArrayList<SwingPoint> candidates = new java.util.ArrayList<>(swings);
        candidates.add(new SwingPoint(null, support.roundPrice(level), true));
        return List.copyOf(candidates);
    }

    private double entryFor(double level, double referenceClose, double entryOffset,
                            double minimumDistanceAtr, double atr, boolean ce) {
        double levelEntry = level + (ce ? entryOffset : -entryOffset);
        double bufferedCloseEntry = referenceClose + (ce ? 1d : -1d) * minimumDistanceAtr * atr;
        return ce ? Math.max(levelEntry, bufferedCloseEntry) : Math.min(levelEntry, bufferedCloseEntry);
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

    private double minimumEntryDistanceAtr(Long appUserId) {
        try {
            String configured = userConfigService.getConfig(appUserId, MIN_ENTRY_DISTANCE_ATR_CONFIG, null);
            if (configured == null || configured.isBlank()) {
                return DEFAULT_MIN_ENTRY_DISTANCE_ATR;
            }
            double value = Double.parseDouble(configured.trim());
            return Double.isFinite(value) && value > 0d ? value : DEFAULT_MIN_ENTRY_DISTANCE_ATR;
        } catch (Exception ignored) {
            return DEFAULT_MIN_ENTRY_DISTANCE_ATR;
        }
    }

    /**
     * The MStock intraday chart is authoritative for today's five-minute candles and pivots.
     * MStock historical data only supplies earlier candles needed for ATR's previous-close context.
     */
    private List<StrategyCandle> loadMStockFiveMinuteChart(ScriptMasterEntity spot, LocalDateTime now) {
        try {
            List<StrategyCandle> intraday = sorted(support.loadCandles(spot, "5minute").candles());
            LocalDateTime from = now.minusDays(15).withHour(9).withMinute(15).withSecond(0).withNano(0);
            StrategySupport.MStockHistoricalIdentity historicalIdentity = support.resolveMStockHistoricalIdentity(spot)
                    .orElseThrow(() -> new IllegalStateException("MStock strategy candle identity is unavailable"));
            MStockHistoricalService.HistoricalResponse response = mStockHistoricalService.getHistoricalCandlesByToken(
                    historicalIdentity.exchange(), historicalIdentity.instrumentToken(), "5minute",
                    from.toLocalDate().toString(), now.toLocalDate().toString());
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
            List<StrategyCandle> candles = sorted(List.copyOf(merged.values()));
            if (candles.isEmpty()) {
                throw new IllegalStateException("MStock returned no five-minute intraday or historical candles");
            }
            if (intraday.isEmpty()) {
                LocalDate latestCompletedSession = candles.get(candles.size() - 1).date();
                log.info("ATR_PREVIOUS_DAY_HISTORICAL_FALLBACK | symbol={} requestedAt={} latestCompletedSession={} candles={}",
                        spot.getTradingSymbol(), now, latestCompletedSession, candles.size());
            }
            return candles;
        } catch (Exception e) {
            throw new IllegalStateException("MStock 5-minute chart is unavailable for "
                    + (spot != null ? spot.getTradingSymbol() : "selected symbol") + ": " + e.getMessage(), e);
        }
    }

    private record SwingPoint(java.time.LocalTime time, double price, boolean referenceLevel) { }
}
