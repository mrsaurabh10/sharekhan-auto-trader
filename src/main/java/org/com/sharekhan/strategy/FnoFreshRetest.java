package org.com.sharekhan.strategy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.com.sharekhan.strategy.Fno925EntryQualificationService.Qualification;
import org.com.sharekhan.strategy.Fno925EntryQualificationService.RetestFrame;
import org.com.sharekhan.strategy.Fno925EntryQualificationService.Signal;

/** A bounded market-only state machine. A restart forgets anchors; historical rejects cannot seed one. */
final class FnoFreshRetest {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FnoFreshRetest.class);
    private static final int MAX_KEYS = 256;
    private static final int MAX_BARS_AFTER_ANCHOR = 3;
    private static final double RETEST_DISTANCE_ATR = 0.35;
    private final Map<String, State> states = new HashMap<>();
    private LocalDate day;
    private static final class State {
        StrategyCandle anchor;
        double originalStop;
        LocalTime processed;
        Qualification result;
    }

    synchronized Qualification evaluate(String key, String side, Qualification baseline, LocalDateTime now) {
        RetestFrame f = baseline.retestFrame();
        if (f == null || !("CE".equals(side) || "PE".equals(side)) || !fresh(f, now)) return baseline;
        if (day != null && now.toLocalDate().isBefore(day)) return baseline;
        if (!now.toLocalDate().equals(day)) { states.clear(); day = now.toLocalDate(); }
        if (!states.containsKey(key) && states.size() >= MAX_KEYS) return baseline;
        State state = states.computeIfAbsent(key, ignored -> new State());
        List<StrategyCandle> bars = f.candles();
        StrategyCandle latest = bars.get(bars.size() - 1);
        if (baseline.qualified()) { state.anchor = null; state.processed = latest.time(); state.result = baseline; return baseline; }
        // Repeated account evaluations may share the same new market signal, never an older candle.
        if (latest.time().equals(state.processed)) return state.result;
        if (state.processed != null && latest.time().isBefore(state.processed)) return baseline;
        state.processed = latest.time(); state.result = baseline;
        boolean ce = "CE".equals(side);
        if (state.anchor != null) {
            StrategyCandle anchor = state.anchor;
            long minutes = Duration.between(anchor.date().atTime(anchor.time()), latest.date().atTime(latest.time())).toMinutes();
            var since = bars.stream().filter(c -> c.time().isAfter(anchor.time())).toList();
            boolean continuous = minutes > 0 && minutes % 5 == 0 && since.size() == minutes / 5;
            for (int i = 0; continuous && i < since.size(); i++)
                continuous = since.get(i).time().equals(anchor.time().plusMinutes(5L * (i + 1)));
            boolean invalidated = since.stream().anyMatch(c -> ce ? c.low() <= state.originalStop : c.high() >= state.originalStop);
            if (!continuous || minutes > MAX_BARS_AFTER_ANCHOR * 5L || invalidated) {
                state.anchor = null;
            } else if (since.size() >= 2) {
                StrategyCandle retest = since.get(since.size() - 2);
                double level = ce ? f.rangeHigh() : f.rangeLow();
                boolean touched = ce ? retest.low() <= level + RETEST_DISTANCE_ATR * f.atr() && retest.close() >= level
                        : retest.high() >= level - RETEST_DISTANCE_ATR * f.atr() && retest.close() <= level;
                boolean pulledBack = ce ? retest.close() < anchor.close() : retest.close() > anchor.close();
                boolean confirmed = ce ? latest.close() > latest.open() && latest.close() > retest.high() && latest.close() > level
                        : latest.close() < latest.open() && latest.close() < retest.low() && latest.close() < level;
                double stop = round(ce ? Math.min(retest.low(), latest.low()) : Math.max(retest.high(), latest.high()));
                double entry = round(latest.close());
                double risk = ce ? entry - stop : stop - entry;
                // Hard ceiling stays 2x ATR even if a future configuration is accidentally widened.
                double cap = Math.min(2d, f.maxRiskAtrMultiplier()) * f.atr();
                if (touched && pulledBack && confirmed && f.filterFailure() == null && risk > 0 && risk <= cap) {
                    state.result = Qualification.qualified(new Signal(entry, stop, round(f.atr()), f.rangeHigh(),
                            f.rangeLow(), latest, "MORNING_ORB_FRESH_RETEST"));
                    log.info("FNO_FRESH_RETEST | key={} | anchor={} | retest={} | signal={} | entry={} | stop={} | atr={}",
                            key, anchor.time(), retest.time(), latest.time(), entry, stop, f.atr());
                    state.anchor = null;
                    return state.result;
                }
            }
        }
        if (state.anchor == null && f.riskOnlyRejection()) {
            state.anchor = latest; state.originalStop = f.originalStop();
            log.info("FNO_RETEST_ANCHOR | key={} | candle={} | originalStop={}", key, latest.time(), f.originalStop());
        }
        return baseline;
    }

    private static boolean fresh(RetestFrame f, LocalDateTime now) {
        if (!f.evaluatedAt().toLocalDate().equals(now.toLocalDate()) || f.candles().isEmpty()
                || !Double.isFinite(f.atr()) || f.atr() <= 0 || !Double.isFinite(f.maxRiskAtrMultiplier())
                || f.maxRiskAtrMultiplier() <= 0) return false;
        long age = Duration.between(f.evaluatedAt(), now).toMillis();
        if (age < 0 || age > 30_000) return false;
        LocalTime expected = now.toLocalTime().withSecond(0).withNano(0);
        expected = expected.minusMinutes(expected.getMinute() % 5).minusMinutes(5);
        StrategyCandle latest = f.candles().get(f.candles().size() - 1);
        return latest.date().equals(now.toLocalDate()) && latest.time().equals(expected)
                && !latest.time().isBefore(LocalTime.of(9, 25)) && !latest.time().isAfter(f.cutoff())
                && f.candles().stream().allMatch(FnoFreshRetest::valid);
    }

    private static boolean valid(StrategyCandle c) {
        return Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low())
                && Double.isFinite(c.close()) && c.low() > 0 && c.low() <= Math.min(c.open(), c.close())
                && c.high() >= Math.max(c.open(), c.close()) && c.high() >= c.low();
    }
    private static double round(double price) { return Math.round(price * 100d) / 100d; }
}
