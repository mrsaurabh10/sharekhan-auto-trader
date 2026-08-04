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
    /** Avoid minor pivots and untradeable, distant prior-day levels. */
    private static final double MIN_SWING_REVERSAL_ATR = 0.75d;
    // A 0.35-ATR buffer admits usable nearby levels while still rejecting an entry
    // already at or beyond the prior close.
    private static final double MIN_ENTRY_DISTANCE_ATR = 0.35d;
    private static final double MAX_ENTRY_DISTANCE_ATR = 3d;
    private static final double AUCTION_OUTLIER_ATR = 2d;
    private static final LocalTime CLOSING_AUCTION_CANDLE_TIME = LocalTime.of(15, 25);
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
        ReferenceClose referenceClose = referenceClose(previousDayCandles, atr);
        double offsetMultiplier = entryOffsetAtr(appUserId);
        double entryOffset = offsetMultiplier * atr;
        List<SwingPoint> swingHighs = withReferenceLevel(meaningfulSwingHighs(previousDayCandles, atr), pdh);
        List<SwingPoint> swingLows = withReferenceLevel(meaningfulSwingLows(previousDayCandles, atr), pdl);
        Optional<SwingPoint> selectedSwing = selectTradableSwing(
                ce ? swingHighs : swingLows, referenceClose.price(), entryOffset, atr, ce);
        List<String> swingAssessments = assessSwingEntries(ce ? swingHighs : swingLows, referenceClose.price(), entryOffset, atr, ce);
        String selectedLevelType = selectedSwing.map(swing -> swing.referenceLevel()
                ? (ce ? "PDH" : "PDL")
                : (ce ? "PD_MEANINGFUL_SWING_HIGH" : "PD_MEANINGFUL_SWING_LOW"))
                .orElse(ce ? "PD_MEANINGFUL_SWING_HIGH" : "PD_MEANINGFUL_SWING_LOW");
        log.info("ATR_PREVIOUS_DAY_LEVELS | symbol={} | referenceDate={} | optionType={} | pdh={} | pdl={} | "
                        + "referenceClose={} | referenceCloseSource={} | swingHighCandidates={} | swingLowCandidates={} | atr75_5m={} | "
                        + "offsetAtr={} | minReversalAtr={} | entryDistanceAtrRange={}-{} | swingEntryAssessments={} | "
                        + "selectedType={} | selectedLevel={} | entry={}",
                spot.getTradingSymbol(), referenceDay, optionType, support.roundPrice(pdh), support.roundPrice(pdl),
                support.roundPrice(referenceClose.price()), referenceClose.source(), swingHighs, swingLows, support.roundPrice(atr), offsetMultiplier,
                MIN_SWING_REVERSAL_ATR, MIN_ENTRY_DISTANCE_ATR, MAX_ENTRY_DISTANCE_ATR, swingAssessments, selectedLevelType,
                selectedSwing.map(SwingPoint::price).map(support::roundPrice).orElse(null),
                selectedSwing.map(swing -> support.roundPrice(entryFor(swing.price(), entryOffset, ce))).orElse(null));

        if (selectedSwing.isEmpty()) {
            return Fno925EntryQualificationService.Qualification.waiting(
                    "no meaningful prior-day " + (ce ? "swing high" : "swing low")
                            + " has an entry between " + MIN_ENTRY_DISTANCE_ATR + " and "
                            + MAX_ENTRY_DISTANCE_ATR + " ATR from the prior-day close");
        }

        double structuralLevel = selectedSwing.get().price();
        double entry = entryFor(structuralLevel, entryOffset, ce);

        double stop = ce ? entry - 2d * atr : entry + 2d * atr;
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), support.roundPrice(stop), pdh, pdl, null,
                ce ? "PENDING_MEANINGFUL_SWING_HIGH_ATR_BREAKOUT" : "PENDING_MEANINGFUL_SWING_LOW_ATR_BREAKDOWN"));
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

    /**
     * MStock can represent the NSE closing auction as the final 15:25 candle.  A large
     * auction-only jump is not usable for judging how far a next-day breakout entry is
     * from the regular-session structure, so retain the 15:20 close in that case.
     */
    private ReferenceClose referenceClose(List<StrategyCandle> candles, double atr) {
        StrategyCandle last = candles.get(candles.size() - 1);
        if (candles.size() < 2 || !CLOSING_AUCTION_CANDLE_TIME.equals(last.time())) {
            return new ReferenceClose(last.close(), "SESSION_CLOSE");
        }
        StrategyCandle prior = candles.get(candles.size() - 2);
        double auctionRange = last.high() - last.low();
        double auctionMove = Math.abs(last.close() - prior.close());
        if (auctionRange >= AUCTION_OUTLIER_ATR * atr && auctionMove >= AUCTION_OUTLIER_ATR * atr) {
            return new ReferenceClose(prior.close(), "PRE_AUCTION_15_20_CLOSE");
        }
        return new ReferenceClose(last.close(), "SESSION_CLOSE");
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
                                                       double entryOffset, double atr, boolean ce) {
        return candidates.stream()
                .filter(swing -> {
                    double entry = entryFor(swing.price(), entryOffset, ce);
                    double distance = ce ? entry - referenceClose : referenceClose - entry;
                    return distance >= MIN_ENTRY_DISTANCE_ATR * atr && distance <= MAX_ENTRY_DISTANCE_ATR * atr;
                })
                .min(Comparator.comparingDouble(swing -> Math.abs(entryFor(swing.price(), entryOffset, ce) - referenceClose)));
    }

    private List<String> assessSwingEntries(List<SwingPoint> candidates, double referenceClose,
                                            double entryOffset, double atr, boolean ce) {
        return candidates.stream().map(swing -> {
            double entry = entryFor(swing.price(), entryOffset, ce);
            double distanceAtr = (ce ? entry - referenceClose : referenceClose - entry) / atr;
            boolean accepted = distanceAtr >= MIN_ENTRY_DISTANCE_ATR && distanceAtr <= MAX_ENTRY_DISTANCE_ATR;
            return swing.time() + "@" + support.roundPrice(swing.price()) + " entry=" + support.roundPrice(entry)
                    + " distanceAtr=" + support.roundPrice(distanceAtr) + " accepted=" + accepted;
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

    private double entryFor(double level, double entryOffset, boolean ce) {
        return level + (ce ? entryOffset : -entryOffset);
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

    private record SwingPoint(java.time.LocalTime time, double price, boolean referenceLevel) { }
    private record ReferenceClose(double price, String source) { }
}
