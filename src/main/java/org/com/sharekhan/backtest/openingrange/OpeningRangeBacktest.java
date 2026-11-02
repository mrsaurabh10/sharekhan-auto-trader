package org.com.sharekhan.backtest.openingrange;

import org.com.sharekhan.backtest.csv.CsvCandleReader;
import org.com.sharekhan.strategy.IndicatorService;
import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;

/** Deterministic research replay. No broker, scheduler, subscription or order dependencies. */
public class OpeningRangeBacktest {
    public record Config(String symbol, LocalDate from, LocalDate to, int supertrendPeriod,
                         double supertrendMultiplier, double targetR, int maxTradesPerDay,
                         double slippagePoints, double costPoints, LocalTime squareOff) {
        public Config {
            if (!Set.of("NIFTY", "BANKNIFTY").contains(symbol)) throw new IllegalArgumentException("Unsupported index");
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Invalid date range");
            if (supertrendPeriod < 2 || supertrendPeriod > 100 || !Double.isFinite(supertrendMultiplier)
                    || supertrendMultiplier <= 0 || supertrendMultiplier > 20 || !Double.isFinite(targetR) || targetR < 2
                    || maxTradesPerDay < 1 || maxTradesPerDay > 2 || !Double.isFinite(slippagePoints) || slippagePoints < 0
                    || !Double.isFinite(costPoints) || costPoints < 0) throw new IllegalArgumentException("Invalid strategy parameters");
            if (squareOff == null || squareOff.isBefore(LocalTime.of(9, 45)) || squareOff.isAfter(LocalTime.of(15, 25))
                    || squareOff.getMinute() % 5 != 0 || squareOff.getSecond() != 0 || squareOff.getNano() != 0)
                throw new IllegalArgumentException("Square-off must be a five-minute boundary from 09:45 to 15:25 IST");
        }
        public static Config defaults(String symbol) {
            return new Config(symbol, null, null, 10, 3, 2, 2, 0, 0, LocalTime.of(15, 20));
        }
    }
    public record Trade(String direction, LocalDateTime signalAt, LocalDateTime entryAt, LocalDateTime exitBarAt,
                        double rangeHigh, double rangeLow, double ema10, double ema20, double supertrend,
                        double entry, double stop, double target, double exit, String reason, double netPoints, double netR) { }
    public record Exclusion(LocalDate date, String reason) { }
    public record Report(Config config, String pnlUnit, int acceptedSessions, List<Exclusion> exclusions,
                         List<Trade> trades, List<org.com.sharekhan.backtest.csv.SupertrendCsvBacktest.Summary> summary) { }
    private final IndicatorService indicators;
    public OpeningRangeBacktest() { this(new IndicatorService()); }
    public OpeningRangeBacktest(IndicatorService indicators) { this.indicators = Objects.requireNonNull(indicators); }

    public Report run(CsvCandleReader.Dataset dataset, Config config) {
        Objects.requireNonNull(dataset); Objects.requireNonNull(config);
        var days = new TreeMap<LocalDate, List<StrategyCandle>>();
        LocalDateTime previous = null;
        for (var bar : dataset.candles()) {
            validate(bar);
            var time = LocalDateTime.of(bar.date(), bar.time());
            if (previous != null && !time.isAfter(previous)) throw new IllegalArgumentException("Duplicate or unsorted bars");
            previous = time;
            days.computeIfAbsent(bar.date(), unused -> new ArrayList<>()).add(bar);
        }
        if (days.isEmpty()) throw new IllegalArgumentException("Empty dataset");
        var history = new ArrayList<StrategyCandle>();
        var trades = new ArrayList<Trade>(); var exclusions = new ArrayList<Exclusion>();
        LocalDate lastDay = null; int accepted = 0; int crossDirection = 0;
        for (var day : days.entrySet()) {
            if (config.to() != null && day.getKey().isAfter(config.to())) break;
            boolean selected = config.from() == null || !day.getKey().isBefore(config.from());
            var bars = day.getValue();
            if (!complete(day.getKey(), bars)) {
                if (selected) exclusions.add(new Exclusion(day.getKey(), "INCOMPLETE_OR_NONREGULAR_SESSION"));
                history.clear(); crossDirection = 0; lastDay = null; continue;
            }
            if (lastDay != null && day.getKey().isAfter(lastDay.plusDays(7))) { history.clear(); crossDirection = 0; }
            lastDay = day.getKey();
            if (selected) accepted++;
            int base = history.size();
            for (int j = 0; j < bars.size(); j += 3) history.add(aggregate(bars.subList(j, j + 3)));
            // Recurrences are causal: values at n use only bars through n.
            var ema10 = indicators.computeEma(history, 10); var ema20 = indicators.computeEma(history, 20);
            var st = indicators.computeSupertrend(history, config.supertrendPeriod(), config.supertrendMultiplier());
            double orHigh = 0, orLow = 0; int filled = 0; Position active = null;
            for (int i = 0; i < bars.size(); i++) {
                var bar = bars.get(i);
                // Fill a pending next-open entry, then assess this five-minute execution bar.
                if (active != null) {
                    String reason = null; double exit = 0; boolean longSide = active.side == 1;
                    if (!bar.time().isBefore(config.squareOff())) { reason = "SQUARE_OFF"; exit = bar.open(); }
                    else if (longSide ? bar.open() <= active.stop : bar.open() >= active.stop) { reason = "STOP_GAP"; exit = bar.open(); }
                    else if (longSide ? bar.open() >= active.target : bar.open() <= active.target) { reason = "TARGET_GAP"; exit = active.target; }
                    else if (longSide ? bar.low() <= active.stop : bar.high() >= active.stop) { reason = "STOP"; exit = active.stop; }
                    else if (longSide ? bar.high() >= active.target : bar.low() <= active.target) { reason = "TARGET"; exit = active.target; }
                    if (reason != null) { trades.add(finish(active, bar, exit, reason, config)); active = null; }
                }
                if (i % 3 != 2) continue;
                int n = base + i / 3;
                var candle = history.get(n);
                if (i == 2) { orHigh = candle.high(); orLow = candle.low(); }
                if (n < 20) continue;
                // Retain the observed crossover until the opposite crossover, including across sessions.
                if (ema10[n - 1] <= ema20[n - 1] && ema10[n] > ema20[n]) crossDirection = 1;
                else if (ema10[n - 1] >= ema20[n - 1] && ema10[n] < ema20[n]) crossDirection = -1;
                if (!selected || i < 5 || active != null || filled >= config.maxTradesPerDay() || i + 1 >= bars.size()) continue;
                var next = bars.get(i + 1);
                if (!next.time().isBefore(config.squareOff())) continue;
                double previousClose = history.get(n - 1).close();
                int side = direction(previousClose, candle.close(), orHigh, orLow, ema10[n], ema20[n], st[n], crossDirection);
                if (side == 0 || !(side == 1 ? next.open() > orHigh : next.open() < orLow)) continue;
                double entry = next.open() + side * config.slippagePoints();
                double stop = side == 1 ? orLow : orHigh; double risk = side * (entry - stop);
                if (!Double.isFinite(risk) || risk <= 0) continue;
                double target = entry + side * config.targetR() * risk;
                active = new Position(side, LocalDateTime.of(candle.date(), candle.time()).plusMinutes(15),
                        LocalDateTime.of(next.date(), next.time()), orHigh, orLow, ema10[n], ema20[n], st[n], entry, stop, target);
                filled++;
            }
            if (active != null) throw new IllegalStateException("Complete session failed to square off");
        }
        return new Report(config, "INPUT_PRICE_POINTS_PER_UNIT; saved index input is SPOT_PROXY, not futures/option/INR P&L",
                accepted, List.copyOf(exclusions), List.copyOf(trades), summary(trades));
    }
    static int direction(double priorClose, double close, double high, double low, double fast, double slow, double st, int cross) {
        if (!Double.isFinite(st) || !Double.isFinite(fast) || !Double.isFinite(slow)) return 0;
        if (cross == 1 && fast > slow && close > st && priorClose <= high && close > high) return 1;
        if (cross == -1 && fast < slow && close < st && priorClose >= low && close < low) return -1;
        return 0;
    }
    private static void validate(StrategyCandle c) {
        if (c == null || c.date() == null || c.time() == null || c.time().getSecond() != 0 || c.time().getNano() != 0
                || c.time().getMinute() % 5 != 0 || !Double.isFinite(c.open()) || !Double.isFinite(c.close())
                || !Double.isFinite(c.high()) || !Double.isFinite(c.low()) || c.low() <= 0
                || c.high() < Math.max(c.open(), c.close()) || c.low() > Math.min(c.open(), c.close()))
            throw new IllegalArgumentException("Invalid five-minute OHLC bar");
    }
    private static boolean complete(LocalDate day, List<StrategyCandle> bars) {
        if (day.getDayOfWeek().getValue() > 5 || bars.size() != 75) return false;
        for (int i = 0; i < 75; i++) if (!bars.get(i).time().equals(LocalTime.of(9, 15).plusMinutes(5L * i))) return false;
        return true;
    }
    private static StrategyCandle aggregate(List<StrategyCandle> bars) {
        var first = bars.get(0); var last = bars.get(2);
        return new StrategyCandle(first.date(), first.time(), first.open(), bars.stream().mapToDouble(StrategyCandle::high).max().orElseThrow(),
                bars.stream().mapToDouble(StrategyCandle::low).min().orElseThrow(), last.close(), 0L);
    }
    private record Position(int side, LocalDateTime signal, LocalDateTime entryTime, double high, double low,
                            double fast, double slow, double st, double entry, double stop, double target) { }
    private static Trade finish(Position p, StrategyCandle bar, double rawExit, String reason, Config c) {
        double exit = rawExit - p.side * c.slippagePoints(); double net = p.side * (exit - p.entry) - c.costPoints();
        return new Trade(p.side == 1 ? "LONG" : "SHORT", p.signal, p.entryTime, LocalDateTime.of(bar.date(), bar.time()),
                p.high, p.low, p.fast, p.slow, p.st, p.entry, p.stop, p.target, exit, reason, net, net / Math.abs(p.entry - p.stop));
    }
    private static List<org.com.sharekhan.backtest.csv.SupertrendCsvBacktest.Summary> summary(List<Trade> trades) {
        var result = new ArrayList<org.com.sharekhan.backtest.csv.SupertrendCsvBacktest.Summary>();
        for (String side : List.of("ALL", "LONG", "SHORT")) {
            var sample = trades.stream().filter(t -> side.equals("ALL") || side.equals(t.direction())).toList();
            double total = 0, peak = 0, dd = 0, gain = 0, loss = 0, r = 0; int wins = 0;
            for (var t : sample) {
                total += t.netPoints(); peak = Math.max(peak, total); dd = Math.max(dd, peak - total); r += t.netR();
                if (t.netPoints() > 1e-9) { wins++; gain += t.netPoints(); } else if (t.netPoints() < -1e-9) loss -= t.netPoints();
            }
            result.add(new org.com.sharekhan.backtest.csv.SupertrendCsvBacktest.Summary(side, sample.size(), wins,
                    sample.isEmpty() ? 0 : 100d * wins / sample.size(), total, sample.isEmpty() ? 0 : r / sample.size(), loss > 0 ? gain / loss : null, dd));
        }
        return List.copyOf(result);
    }
}
