package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
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
    private static final LocalTime DEFAULT_ORB_CUTOFF = LocalTime.of(11, 30);
    private static final LocalTime DEFAULT_RECLAIM_CUTOFF = LocalTime.of(14, 30);
    private static final int ATR_PERIOD = 75;

    @org.springframework.beans.factory.annotation.Autowired
    private SharedMarketDecisions sharedDecisions;
    private final StrategySupport support;
    private final ThreadLocal<EvaluationCycle> cycleContext = new ThreadLocal<>();
    @Value("${app.strategy.fno-0925-mover.fresh-retest-enabled:true}")
    private boolean freshRetestEnabled = true;
    private final FnoFreshRetest freshRetest = new FnoFreshRetest();
    private Clock clock = Clock.system(StrategySupport.MARKET_ZONE);

    @Value("${app.strategy.mover-evaluation.max-cycle-age-ms:30000}")
    private long maxCycleAgeMillis = 30_000L;

    @jakarta.annotation.PostConstruct
    void logFreshRetestPolicy() {
        org.slf4j.LoggerFactory.getLogger(Fno925EntryQualificationService.class).info(
                "FNO_FRESH_RETEST_POLICY | enabled={} | maxBarsAfterRejection=3 | retestDistanceAtr=0.35 | hardRiskCapAtr=2.0",
                freshRetestEnabled);
    }

    /** Market-only results, shared by all accounts in one scheduler pass; never contains account orders. */
    public static final class EvaluationCycle {
        private final String id = java.util.UUID.randomUUID().toString();
        private final LocalDateTime evaluatedAt;
        private final java.util.Map<String, Qualification> qualifications = new java.util.concurrent.ConcurrentHashMap<>();

        public EvaluationCycle(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
        public String id() { return id; }
        public LocalDateTime evaluatedAt() { return evaluatedAt; }
    }

    public void withinCycle(EvaluationCycle cycle, Runnable work) {
        if (cycleContext.get() != null) throw new IllegalStateException("Nested mover cycles are not supported");
        cycleContext.set(cycle);
        try { work.run(); } finally { cycleContext.remove(); }
    }

    /** Queued work cannot revive an old completed candle or a prior trading day. */
    public boolean isCycleExpired() {
        EvaluationCycle cycle = cycleContext.get();
        if (cycle == null) return false; // Direct/manual apply retains its existing current-time evaluation.
        LocalDateTime current = LocalDateTime.now(clock);
        long age = Duration.between(cycle.evaluatedAt, current).toMillis();
        return age < 0 || age > Math.max(1L, maxCycleAgeMillis)
                || !cycle.evaluatedAt.toLocalDate().equals(current.toLocalDate())
                || cycle.evaluatedAt.toLocalTime().toSecondOfDay() / 300
                    != current.toLocalTime().toSecondOfDay() / 300;
    }

    public String cycleTiming() {
        EvaluationCycle cycle = cycleContext.get();
        return cycle == null ? "cycle=direct" : "cycle=" + cycle.id + ", signalEvaluatedAt=" + cycle.evaluatedAt
                + ", evaluationDelayMs=" + Duration.between(cycle.evaluatedAt, LocalDateTime.now(clock)).toMillis();
    }


    @Value("${app.strategy.fno-0925-mover.orb-volume-multiplier:0.75}")
    private double orbVolumeMultiplier;

    @Value("${app.strategy.fno-0925-mover.base-volume-multiplier:1.0}")
    private double baseVolumeMultiplier;

    @Value("${app.strategy.fno-0925-mover.volume-lookback:5}")
    private int volumeLookback;

    @Value("${app.strategy.fno-0925-mover.max-opposing-wick-to-range:0.65}")
    private double maxOpposingWickToRange;

    @Value("${app.strategy.fno-0925-mover.min-body-to-range:0.30}")
    private double minBodyToRange;

    @Value("${app.strategy.fno-0925-mover.max-risk-atr-multiplier:2.0}")
    private double maxRiskAtrMultiplier;

    @Value("${app.strategy.fno-0925-mover.orb-cutoff:11:30}")
    private LocalTime orbCutoff;

    @Value("${app.strategy.fno-0925-mover.vwap-reclaim-cutoff:14:30}")
    private LocalTime vwapReclaimCutoff;

    @Value("${app.strategy.fno-0925-mover.reclaim-base-candles:3}")
    private int reclaimBaseCandles;

    @Value("${app.strategy.fno-0925-mover.reclaim-vwap-confirmations:1}")
    private int reclaimVwapConfirmations;

    @Value("${app.strategy.fno-0925-mover.vwap-side-tolerance:0.001}")
    private double vwapSideTolerance;

    @Value("${app.strategy.fno-0925-mover.vwap-direction-tolerance:0.0003}")
    private double vwapDirectionTolerance;

    @Value("${app.strategy.fno-0925-mover.vwap-retest-max-distance-atr:0.35}")
    private double vwapRetestMaxDistanceAtr = 0.35d;

    @Value("${app.strategy.fno-0925-mover.vwap-max-extension-atr:1.5}")
    private double vwapMaxExtensionAtr = 1.5d;

    @Value("${app.strategy.fno-0925-mover.base-break-min-atr:0.20}")
    private double baseBreakMinAtr = 0.20d;

    @Value("${app.strategy.fno-0925-mover.session-extreme-buffer-atr:0.50}")
    private double sessionExtremeBufferAtr = 0.50d;

    public Qualification qualify(Fno925Candidate candidate, LocalDateTime now) {
        return qualify(candidate, now, false);
    }

    /** Mover templates require the completed signal candle to agree with the entry direction. */
    public Qualification qualifyWithDirectionalCandle(Fno925Candidate candidate, LocalDateTime now) {
        EvaluationCycle cycle = cycleContext.get();
        if (cycle == null) return qualify(candidate, now, true);
        if (isCycleExpired()) return Qualification.waiting("Mover evaluation expired; waiting for a fresh cycle");
        String key = candidate.spot().getExchange() + ":" + candidate.spot().getScripCode()
                + ":" + candidate.symbol() + ":" + candidate.optionType();
        Qualification result = cycle.qualifications.computeIfAbsent(key,
                ignored -> qualify(candidate, cycle.evaluatedAt, true));
        return isCycleExpired() ? Qualification.waiting("Mover evaluation expired; waiting for a fresh cycle") : result;
    }

    /** FNO mover only: Stock mover and manually curated templates retain their existing paths. */
    public Qualification qualifyMoverWithFreshRetest(Fno925Candidate candidate, LocalDateTime now) {
        Qualification baseline = qualifyWithDirectionalCandle(candidate, now);
        if (!freshRetestEnabled || isCycleExpired() || baseline.retestFrame() == null) return baseline;
        String key = candidate.spot().getExchange() + ":" + candidate.spot().getScripCode()
                + ":" + candidate.symbol() + ":" + candidate.optionType();
        Qualification result = freshRetest.evaluate(key, candidate.optionType(), baseline, LocalDateTime.now(clock));
        return isCycleExpired() ? Qualification.waiting("Mover evaluation expired; waiting for a fresh cycle") : result;
    }

    private Qualification qualify(Fno925Candidate candidate, LocalDateTime now, boolean requireDirectionalCandle) {
        return sharedDecisions == null ? qualifyMarket(candidate, now, requireDirectionalCandle)
                : sharedDecisions.share("FNO_BREAKOUT", candidate.symbol(), candidate.spot().getExchange() + ":"
                        + candidate.spot().getScripCode() + ":" + candidate.optionType() + ":" + requireDirectionalCandle,
                        now, () -> qualifyMarket(candidate, now, requireDirectionalCandle));
    }

    private Qualification qualifyMarket(Fno925Candidate candidate, LocalDateTime now, boolean requireDirectionalCandle) {
        CandleLoad load = support.loadCandlesWithHistoricalFallback(candidate.spot(), ATR_PERIOD + 1);
        List<StrategyCandle> allCandles = load.candles().stream()
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                .toList();
        Qualification baseline = qualifyLoaded(candidate, now, requireDirectionalCandle, allCandles);
        if (!freshRetestEnabled || !requireDirectionalCandle) return baseline;
        try {
            var frame = retestFrame(candidate, now, allCandles, baseline);
            return new Qualification(baseline.signal(), baseline.reason(), frame);
        } catch (RuntimeException unavailable) {
            // Missing retest inputs cannot invalidate the existing qualification paths.
            return baseline;
        }
    }

    private Qualification qualifyLoaded(Fno925Candidate candidate, LocalDateTime now,
                                         boolean requireDirectionalCandle, List<StrategyCandle> allCandles) {
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
        if (requireDirectionalCandle) {
            boolean bullish = "CE".equalsIgnoreCase(candidate.optionType());
            boolean matchesDirection = bullish ? breakout.close() > breakout.open() : breakout.close() < breakout.open();
            if (!matchesDirection) {
                return Qualification.waiting(bullish
                        ? "entry requires a bullish completed five-minute signal candle (close > open)"
                        : "entry requires a bearish completed five-minute signal candle (close < open)");
            }
        }
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

    private RetestFrame retestFrame(Fno925Candidate candidate, LocalDateTime now,
                                     List<StrategyCandle> source, Qualification baseline) {
        // Retest inputs use completed, regular-session bars only, including the ATR seed.
        var completed = source.stream().filter(c -> c.date().isBefore(now.toLocalDate())
                || (c.date().equals(now.toLocalDate()) && !c.time().plusMinutes(5).isAfter(now.toLocalTime())))
                .filter(c -> !c.time().isBefore(OR_START) && c.time().isBefore(LocalTime.of(15, 30)))
                .toList();
        var today = completed.stream().filter(c -> c.date().equals(now.toLocalDate())).toList();
        if (today.size() < 3) return null;
        var range = today.stream().filter(c -> c.time().isBefore(OR_END)).toList();
        if (range.size() != 2 || !range.get(0).time().equals(OR_START)
                || !range.get(1).time().equals(OR_START.plusMinutes(5))) return null;
        StrategyCandle latest = today.get(today.size() - 1);
        double high = range.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double low = range.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        var orb = qualifyMorningOrb(candidate, today, completed, latest, high, low);
        double stop = openingRangeStructuralStop(today, latest, candidate.optionType(), high, low);
        boolean directional = "CE".equals(candidate.optionType()) ? latest.close() > latest.open() : latest.close() < latest.open();
        boolean riskOnly = directional && !orb.qualified() && orb.reason().startsWith("structural risk ")
                && baseline.reason() != null && baseline.reason().startsWith("ORB: structural risk ");
        String filter = filterFailure(today, latest, candidate.optionType(),
                recentPostOpenVolumeReference(today, latest), orbVolumeMultiplier).orElse(null);
        return new RetestFrame(now, List.copyOf(today), atr(completed), high, low, stop, riskOnly,
                filter, baseline.qualified(), baseline.reason(), maxRiskAtrMultiplier,
                orbCutoff != null ? orbCutoff : DEFAULT_ORB_CUTOFF);
    }

    /** Market-only retest input; never contains account orders or broker credentials. */
    public record RetestFrame(LocalDateTime evaluatedAt, List<StrategyCandle> candles, double atr,
                              double rangeHigh, double rangeLow, double originalStop, boolean riskOnlyRejection,
                              String filterFailure, boolean baselineQualified, String baselineReason,
                              double maxRiskAtrMultiplier, LocalTime cutoff) { }

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
        double atr = atr(allCandles);
        if (atr <= 0d) {
            return Qualification.waiting("ATR(75) is unavailable");
        }
        if (!holdsReclaimedVwap(todayCandles, base, breakout, candidate.optionType())) {
            return Qualification.waiting("VWAP reclaim is not confirmed");
        }
        if (!hasRecentVwapRetest(todayCandles, base, candidate.optionType(), atr)) {
            return Qualification.waiting("VWAP pullback/retest is not recent");
        }
        double baseBreak = "CE".equalsIgnoreCase(candidate.optionType())
                ? base.stream().mapToDouble(StrategyCandle::high).max().orElseThrow()
                : base.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        if ("CE".equalsIgnoreCase(candidate.optionType()) ? breakout.close() <= baseBreak : breakout.close() >= baseBreak) {
            return Qualification.waiting("no VWAP-reclaim base breakout");
        }
        if (!hasDecisiveBaseBreak(breakout, baseBreak, candidate.optionType(), atr)) {
            return Qualification.waiting("VWAP-reclaim base breakout is too shallow");
        }
        if (isTooExtendedFromVwap(todayCandles, breakout, candidate.optionType(), atr)) {
            return Qualification.waiting("breakout is too extended from VWAP");
        }
        if (isNearPriorSessionExtreme(beforeBreakout, breakout, candidate.optionType(), atr)) {
            return Qualification.waiting("breakout is too close to prior session support/resistance");
        }
        Optional<String> failure = filterFailure(todayCandles, breakout, candidate.optionType(), base, baseVolumeMultiplier);
        if (failure.isPresent()) {
            return Qualification.waiting(failure.get());
        }
        double stop = reclaimBaseStructuralStop(todayCandles, base, breakout, candidate.optionType());
        return validateSignal(candidate.optionType(), allCandles, breakout, stop, rangeHigh, rangeLow, "VWAP_RECLAIM_BASE_BREAKOUT");
    }

    private boolean hasRecentVwapRetest(List<StrategyCandle> candles,
                                        List<StrategyCandle> base,
                                        String optionType,
                                        double atr) {
        double maxDistance = Math.max(0d, vwapRetestMaxDistanceAtr) * atr;
        for (StrategyCandle candle : base) {
            Double candleVwap = vwap(candles, candle);
            if (candleVwap == null) {
                continue;
            }
            boolean retested = "CE".equalsIgnoreCase(optionType)
                    ? candle.low() <= candleVwap + maxDistance
                    : candle.high() >= candleVwap - maxDistance;
            if (retested) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDecisiveBaseBreak(StrategyCandle breakout,
                                         double baseBreak,
                                         String optionType,
                                         double atr) {
        double minimumBreak = Math.max(0d, baseBreakMinAtr) * atr;
        double extension = "CE".equalsIgnoreCase(optionType)
                ? breakout.close() - baseBreak
                : baseBreak - breakout.close();
        return extension >= minimumBreak;
    }

    private boolean isTooExtendedFromVwap(List<StrategyCandle> candles,
                                          StrategyCandle breakout,
                                          String optionType,
                                          double atr) {
        Double breakoutVwap = vwap(candles, breakout);
        if (breakoutVwap == null) {
            return true;
        }
        double maximumExtension = Math.max(0d, vwapMaxExtensionAtr) * atr;
        double extension = "CE".equalsIgnoreCase(optionType)
                ? breakout.close() - breakoutVwap
                : breakoutVwap - breakout.close();
        return extension > maximumExtension;
    }

    private boolean isNearPriorSessionExtreme(List<StrategyCandle> beforeBreakout,
                                              StrategyCandle breakout,
                                              String optionType,
                                              double atr) {
        if (beforeBreakout.isEmpty()) {
            return false;
        }
        double buffer = Math.max(0d, sessionExtremeBufferAtr) * atr;
        if ("CE".equalsIgnoreCase(optionType)) {
            double priorHigh = beforeBreakout.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
            return priorHigh - breakout.close() <= buffer;
        }
        double priorLow = beforeBreakout.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        return breakout.close() - priorLow <= buffer;
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
        return Qualification.qualified(new Signal(entry, support.roundPrice(structuralStop), support.roundPrice(atr),
                rangeHigh, rangeLow, breakout, setup));
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
        double sideTolerance = Math.max(0d, vwapSideTolerance);
        if (vwap == null || ("CE".equalsIgnoreCase(optionType)
                ? breakout.close() < vwap * (1d - sideTolerance)
                : breakout.close() > vwap * (1d + sideTolerance))) {
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
        int confirmationsRequired = Math.min(Math.max(1, reclaimVwapConfirmations), base.size());
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
        if (priorVwap == null || breakoutVwap == null) {
            return false;
        }
        double directionTolerance = Math.max(0d, vwapDirectionTolerance);
        return "CE".equalsIgnoreCase(optionType)
                ? breakoutVwap >= priorVwap * (1d - directionTolerance)
                : breakoutVwap <= priorVwap * (1d + directionTolerance);
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
                         double atr,
                         double openingRangeHigh,
                         double openingRangeLow,
                         StrategyCandle breakoutCandle,
                         String setup) {
    }

    public record Qualification(Signal signal, String reason, RetestFrame retestFrame) {
        public Qualification(Signal signal, String reason) { this(signal, reason, null); }
        static Qualification qualified(Signal signal) { return new Qualification(signal, null); }
        static Qualification waiting(String reason) { return new Qualification(null, reason); }
        public boolean qualified() { return signal != null; }
    }
}
