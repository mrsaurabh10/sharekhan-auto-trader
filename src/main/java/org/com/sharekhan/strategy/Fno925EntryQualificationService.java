package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Entry-filter pipeline shared by the F&O 09:25 mover and manually curated F&O strategies. */
@Component
@RequiredArgsConstructor
public class Fno925EntryQualificationService {

    private static final LocalTime OR_START = LocalTime.of(9, 15);
    private static final LocalTime OR_END = LocalTime.of(9, 25);
    private static final LocalTime DEFAULT_ORB_CUTOFF = LocalTime.of(10, 45);
    private static final LocalTime DEFAULT_RECLAIM_CUTOFF = LocalTime.of(13, 0);
    private static final int ATR_PERIOD = 75;

    private final StrategySupport support;

    @Value("${app.strategy.fno-0925-mover.orb-volume-multiplier:0.9}")
    private double orbVolumeMultiplier;

    @Value("${app.strategy.fno-0925-mover.base-volume-multiplier:1.15}")
    private double baseVolumeMultiplier;

    @Value("${app.strategy.fno-0925-mover.volume-lookback:5}")
    private int volumeLookback;

    @Value("${app.strategy.fno-0925-mover.max-opposing-wick-to-range:0.55}")
    private double maxOpposingWickToRange;

    @Value("${app.strategy.fno-0925-mover.min-body-to-range:0.40}")
    private double minBodyToRange;

    @Value("${app.strategy.fno-0925-mover.max-risk-atr-multiplier:1.5}")
    private double maxRiskAtrMultiplier;

    @Value("${app.strategy.fno-0925-mover.orb-cutoff:10:45}")
    private LocalTime orbCutoff;

    @Value("${app.strategy.fno-0925-mover.vwap-reclaim-cutoff:13:00}")
    private LocalTime vwapReclaimCutoff;

    @Value("${app.strategy.fno-0925-mover.reclaim-base-candles:3}")
    private int reclaimBaseCandles;

    public Qualification qualify(Fno925Candidate candidate, LocalDateTime now) {
        CandleLoad load = support.loadCandlesWithHistoricalFallback(candidate.spot(), ATR_PERIOD + 1);
        List<StrategyCandle> allCandles = load.candles().stream()
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                .toList();
        LocalDate today = now.toLocalDate();
        List<StrategyCandle> todayCandles = allCandles.stream()
                .filter(candle -> today.equals(candle.date()))
                .sorted(Comparator.comparing(StrategyCandle::time))
                .toList();
        List<StrategyCandle> openingRange = todayCandles.stream()
                .filter(candle -> !candle.time().isBefore(OR_START) && candle.time().isBefore(OR_END))
                .toList();
        if (openingRange.size() < 2) {
            return Qualification.waiting("opening range is incomplete");
        }
        double rangeHigh = openingRange.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double rangeLow = openingRange.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();

        List<StrategyCandle> completedBreakouts = todayCandles.stream()
                .filter(candle -> !candle.time().isBefore(OR_END))
                .filter(candle -> !candle.time().plusMinutes(StrategySupport.CANDLE_MINUTES).isAfter(now.toLocalTime()))
                .toList();
        if (completedBreakouts.isEmpty()) {
            return Qualification.waiting("waiting for the first completed five-minute candle after 09:25");
        }

        // Do not backfill an old candle: an evaluation must act on the most recently completed candle only.
        StrategyCandle breakout = completedBreakouts.get(completedBreakouts.size() - 1);
        Qualification orb = qualifyMorningOrb(candidate, todayCandles, allCandles, breakout, rangeHigh, rangeLow);
        if (orb.qualified()) {
            return orb;
        }
        Qualification reclaim = qualifyVwapReclaimBaseBreak(candidate, todayCandles, allCandles, breakout, rangeHigh, rangeLow);
        if (reclaim.qualified()) {
            return reclaim;
        }
        return Qualification.waiting("ORB: " + orb.reason() + "; VWAP reclaim: " + reclaim.reason());
    }

    private Qualification qualifyMorningOrb(Fno925Candidate candidate,
                                            List<StrategyCandle> todayCandles,
                                            List<StrategyCandle> allCandles,
                                            StrategyCandle breakout,
                                            double rangeHigh,
                                            double rangeLow) {
        LocalTime cutoff = orbCutoff != null ? orbCutoff : DEFAULT_ORB_CUTOFF;
        if (breakout.time().isAfter(cutoff)) {
            return Qualification.waiting("morning ORB window closed");
        }
        if (!breaksRange(candidate.optionType(), breakout, rangeHigh, rangeLow)) {
            return Qualification.waiting("no opening-range breakout");
        }
        Optional<String> failure = filterFailure(todayCandles, breakout, candidate.optionType(),
                recentPostOpenVolumeReference(todayCandles, breakout), orbVolumeMultiplier);
        if (failure.isPresent()) {
            return Qualification.waiting(failure.get());
        }
        double stop = openingRangeStructuralStop(todayCandles, breakout, candidate.optionType(), rangeHigh, rangeLow);
        return validateSignal(candidate.optionType(), allCandles, breakout, stop, rangeHigh, rangeLow, "MORNING_ORB");
    }

    private Qualification qualifyVwapReclaimBaseBreak(Fno925Candidate candidate,
                                                       List<StrategyCandle> todayCandles,
                                                       List<StrategyCandle> allCandles,
                                                       StrategyCandle breakout,
                                                       double rangeHigh,
                                                       double rangeLow) {
        LocalTime cutoff = vwapReclaimCutoff != null ? vwapReclaimCutoff : DEFAULT_RECLAIM_CUTOFF;
        if (breakout.time().isAfter(cutoff)) {
            return Qualification.waiting("VWAP reclaim window closed");
        }
        int baseSize = Math.max(3, reclaimBaseCandles);
        List<StrategyCandle> beforeBreakout = todayCandles.stream()
                .filter(candle -> candle.time().isBefore(breakout.time()))
                .toList();
        if (beforeBreakout.size() < baseSize) {
            return Qualification.waiting("insufficient candles for VWAP reclaim base");
        }
        List<StrategyCandle> base = beforeBreakout.subList(beforeBreakout.size() - baseSize, beforeBreakout.size());
        if (!holdsReclaimedVwap(todayCandles, base, breakout, candidate.optionType())) {
            return Qualification.waiting("VWAP reclaim is not confirmed");
        }
        double baseBreak = "CE".equalsIgnoreCase(candidate.optionType())
                ? base.stream().mapToDouble(StrategyCandle::high).max().orElseThrow()
                : base.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        if ("CE".equalsIgnoreCase(candidate.optionType()) ? breakout.close() <= baseBreak : breakout.close() >= baseBreak) {
            return Qualification.waiting("no VWAP-reclaim base breakout");
        }
        Optional<String> failure = filterFailure(todayCandles, breakout, candidate.optionType(), base, baseVolumeMultiplier);
        if (failure.isPresent()) {
            return Qualification.waiting(failure.get());
        }
        double stop = reclaimBaseStructuralStop(todayCandles, base, breakout, candidate.optionType());
        return validateSignal(candidate.optionType(), allCandles, breakout, stop, rangeHigh, rangeLow, "VWAP_RECLAIM_BASE_BREAKOUT");
    }

    private Qualification validateSignal(String optionType,
                                         List<StrategyCandle> allCandles,
                                         StrategyCandle breakout,
                                         double structuralStop,
                                         double rangeHigh,
                                         double rangeLow,
                                         String setup) {
        double entry = support.roundPrice(breakout.close());
        if (!validRisk(optionType, entry, structuralStop)) {
            return Qualification.waiting("structural stop does not produce positive risk");
        }
        double atr = atr(allCandles);
        if (atr <= 0d) {
            return Qualification.waiting("ATR(75) is unavailable");
        }
        double risk = Math.abs(entry - structuralStop);
        if (risk > atr * maxRiskAtrMultiplier) {
            return Qualification.waiting("structural risk " + support.roundPrice(risk) + " exceeds "
                    + maxRiskAtrMultiplier + "x ATR(75)");
        }
        return Qualification.qualified(new Signal(entry, support.roundPrice(structuralStop), rangeHigh, rangeLow, breakout, setup));
    }

    private boolean breaksRange(String optionType, StrategyCandle candle, double rangeHigh, double rangeLow) {
        return "CE".equalsIgnoreCase(optionType) ? candle.close() > rangeHigh : candle.close() < rangeLow;
    }

    private Optional<String> filterFailure(List<StrategyCandle> candles,
                                           StrategyCandle breakout,
                                           String optionType,
                                           List<StrategyCandle> volumeReference,
                                           double volumeMultiplier) {
        List<StrategyCandle> referenceWithVolume = volumeReference.stream()
                .filter(StrategyCandle::hasVolume)
                .toList();
        if (!breakout.hasVolume() || referenceWithVolume.isEmpty()) {
            return Optional.of("volume baseline is unavailable");
        }
        double medianVolume = medianVolume(referenceWithVolume);
        if (breakout.volume() < medianVolume * volumeMultiplier) {
            return Optional.of("breakout volume is below " + volumeMultiplier + "x recent median");
        }
        Double vwap = vwap(candles, breakout);
        if (vwap == null || ("CE".equalsIgnoreCase(optionType) ? breakout.close() <= vwap : breakout.close() >= vwap)) {
            return Optional.of("breakout close is on the wrong side of VWAP");
        }
        double body = Math.abs(breakout.close() - breakout.open());
        double range = breakout.high() - breakout.low();
        if (body <= 0d || range <= 0d) {
            return Optional.of("breakout candle has no body");
        }
        if (body / range < minBodyToRange) {
            return Optional.of("breakout candle body is too small for its range");
        }
        double opposingWick = "CE".equalsIgnoreCase(optionType)
                ? Math.min(breakout.open(), breakout.close()) - breakout.low()
                : breakout.high() - Math.max(breakout.open(), breakout.close());
        if (opposingWick / range > maxOpposingWickToRange) {
            return Optional.of("breakout candle has an immediate rejection wick");
        }
        return Optional.empty();
    }

    private List<StrategyCandle> recentPostOpenVolumeReference(List<StrategyCandle> candles, StrategyCandle breakout) {
        List<StrategyCandle> postOpen = candles.stream()
                .filter(candle -> !candle.time().isBefore(OR_END))
                .filter(candle -> candle.time().isBefore(breakout.time()))
                .filter(StrategyCandle::hasVolume)
                .toList();
        int start = Math.max(0, postOpen.size() - Math.max(1, volumeLookback));
        return postOpen.subList(start, postOpen.size());
    }

    private double medianVolume(List<StrategyCandle> candles) {
        List<Long> volumes = candles.stream().map(StrategyCandle::volume).sorted().toList();
        int middle = volumes.size() / 2;
        return volumes.size() % 2 == 0
                ? (volumes.get(middle - 1) + volumes.get(middle)) / 2d
                : volumes.get(middle);
    }

    private Double vwap(List<StrategyCandle> candles, StrategyCandle through) {
        double priceVolume = 0d;
        long totalVolume = 0L;
        for (StrategyCandle candle : candles) {
            if (candle.time().isAfter(through.time()) || !candle.hasVolume()) continue;
            priceVolume += ((candle.high() + candle.low() + candle.close()) / 3d) * candle.volume();
            totalVolume += candle.volume();
        }
        return totalVolume > 0L ? priceVolume / totalVolume : null;
    }

    private boolean holdsReclaimedVwap(List<StrategyCandle> candles,
                                       List<StrategyCandle> base,
                                       StrategyCandle breakout,
                                       String optionType) {
        int confirmationsRequired = Math.min(2, base.size());
        List<StrategyCandle> confirmations = base.subList(base.size() - confirmationsRequired, base.size());
        for (StrategyCandle candle : confirmations) {
            Double candleVwap = vwap(candles, candle);
            if (candleVwap == null || ("CE".equalsIgnoreCase(optionType)
                    ? candle.close() <= candleVwap
                    : candle.close() >= candleVwap)) {
                return false;
            }
        }
        Double priorVwap = vwap(candles, base.get(base.size() - 1));
        Double breakoutVwap = vwap(candles, breakout);
        return priorVwap != null && breakoutVwap != null
                && ("CE".equalsIgnoreCase(optionType) ? breakoutVwap >= priorVwap : breakoutVwap <= priorVwap);
    }

    private double openingRangeStructuralStop(List<StrategyCandle> candles,
                                              StrategyCandle breakout,
                                              String optionType,
                                              double rangeHigh,
                                              double rangeLow) {
        List<StrategyCandle> postRangeThroughBreakout = candles.stream()
                .filter(candle -> !candle.time().isBefore(OR_END))
                .filter(candle -> !candle.time().isAfter(breakout.time()))
                .toList();
        if ("CE".equalsIgnoreCase(optionType)) {
            return Math.max(rangeLow, latestConfirmedSwingLow(postRangeThroughBreakout).orElse(rangeLow));
        }
        return Math.min(rangeHigh, latestConfirmedSwingHigh(postRangeThroughBreakout).orElse(rangeHigh));
    }

    private double reclaimBaseStructuralStop(List<StrategyCandle> candles,
                                             List<StrategyCandle> base,
                                             StrategyCandle breakout,
                                             String optionType) {
        List<StrategyCandle> throughBreakout = candles.stream()
                .filter(candle -> !candle.time().isAfter(breakout.time()))
                .toList();
        if ("CE".equalsIgnoreCase(optionType)) {
            double baseLow = base.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
            return Math.max(baseLow, latestConfirmedSwingLow(throughBreakout).orElse(baseLow));
        }
        double baseHigh = base.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        return Math.min(baseHigh, latestConfirmedSwingHigh(throughBreakout).orElse(baseHigh));
    }

    private Optional<Double> latestConfirmedSwingLow(List<StrategyCandle> candles) {
        Double latest = null;
        for (int index = 1; index < candles.size() - 1; index++) {
            double low = candles.get(index).low();
            if (low < candles.get(index - 1).low() && low < candles.get(index + 1).low()) {
                latest = low;
            }
        }
        return Optional.ofNullable(latest);
    }

    private Optional<Double> latestConfirmedSwingHigh(List<StrategyCandle> candles) {
        Double latest = null;
        for (int index = 1; index < candles.size() - 1; index++) {
            double high = candles.get(index).high();
            if (high > candles.get(index - 1).high() && high > candles.get(index + 1).high()) {
                latest = high;
            }
        }
        return Optional.ofNullable(latest);
    }

    private double atr(List<StrategyCandle> candles) {
        if (candles.size() < ATR_PERIOD + 1) {
            return 0d;
        }
        List<StrategyCandle> tail = candles.subList(candles.size() - (ATR_PERIOD + 1), candles.size());
        double total = 0d;
        for (int index = 1; index < tail.size(); index++) {
            StrategyCandle previous = tail.get(index - 1);
            StrategyCandle current = tail.get(index);
            total += Math.max(current.high() - current.low(), Math.max(
                    Math.abs(current.high() - previous.close()), Math.abs(current.low() - previous.close())));
        }
        return total / ATR_PERIOD;
    }

    private boolean validRisk(String optionType, double entry, double stop) {
        return "CE".equalsIgnoreCase(optionType) ? entry > stop : entry < stop;
    }

    public record Signal(double entryPrice,
                         double stopLoss,
                         double openingRangeHigh,
                         double openingRangeLow,
                         StrategyCandle breakoutCandle,
                         String setup) {
    }

    public record Qualification(Signal signal, String reason) {
        static Qualification qualified(Signal signal) { return new Qualification(signal, null); }
        static Qualification waiting(String reason) { return new Qualification(null, reason); }
        public boolean qualified() { return signal != null; }
    }
}
