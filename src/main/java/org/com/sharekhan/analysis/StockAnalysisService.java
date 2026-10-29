package org.com.sharekhan.analysis;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

/** Read-only, cash-market research. Has no order, subscription or broker-login dependency. */
@Service
public class StockAnalysisService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final ScriptMasterRepository scripts;
    private final SharekhanHistoricalService history;
    private final StockAnalysisShoonyaFeed shoonya;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock providerSlot = new java.util.concurrent.locks.ReentrantLock();
    private long lastFetchNanos;
    record Cached(Instant until, Report report) {}

    @org.springframework.beans.factory.annotation.Autowired
    public StockAnalysisService(ScriptMasterRepository scripts, SharekhanHistoricalService history, StockAnalysisShoonyaFeed shoonya) {
        this(scripts, history, shoonya, Clock.system(IST));
    }
    StockAnalysisService(ScriptMasterRepository scripts, SharekhanHistoricalService history) { this(scripts, history, null, Clock.system(IST)); }
    StockAnalysisService(ScriptMasterRepository scripts, SharekhanHistoricalService history, Clock clock) {
        this(scripts, history, null, clock);
    }
    StockAnalysisService(ScriptMasterRepository scripts, SharekhanHistoricalService history, StockAnalysisShoonyaFeed shoonya, Clock clock) {
        this.scripts = scripts; this.history = history; this.shoonya = shoonya; this.clock = clock;
    }
    public record Instrument(Integer scripCode, String symbol, String exchange) {}
    public record Bar(LocalDate date, LocalTime time, double open, double high, double low, double close, Long volume) {}
    public record Check(String name, String status, String detail) {}
    public record Indicators(Double sma20, Double sma50, Double sma200, Double rsi14, Double atr14,
                             Double vwap, Double openingHigh, Double openingLow, Double priorClose,
                             Double rangeLow20, Double rangeHigh20) {}
    public record Report(Instrument instrument, String mode, String interval, String provider, String timezone,
                         Instant fetchedAt, LocalDateTime asOf, String dataStatus, String trend,
                         int received, int rejected, int duplicates, int gaps, List<Bar> candles,
                         Indicators indicators, List<Check> checks, List<String> warnings) {}

    static String exchange(String value) {
        String ex = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NC", "BC").contains(ex)) throw bad("Choose NSE cash (NC) or BSE cash (BC).");
        return ex;
    }
    static boolean cash(ScriptMasterEntity s) {
        return s != null && s.getScripCode() != null && s.getTradingSymbol() != null && s.getExchange() != null
                && (s.getExpiry() == null || s.getExpiry().isBlank())
                && (s.getStrikePrice() == null || s.getStrikePrice() == 0)
                && (s.getOptionType() == null || s.getOptionType().isBlank());
    }
    public List<Instrument> search(String exchange, String query) {
        String ex = exchange(exchange), q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (q.length() < 2) return List.of();
        if (q.length() > 60) throw bad("Search must be 60 characters or fewer.");
        return scripts.findByExchangeIgnoreCase(ex).stream().filter(StockAnalysisService::cash)
                .filter(s -> ex.equalsIgnoreCase(s.getExchange().trim()))
                .filter(s -> s.getTradingSymbol().toUpperCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing((ScriptMasterEntity s) -> !s.getTradingSymbol().equalsIgnoreCase(q))
                        .thenComparing(ScriptMasterEntity::getTradingSymbol).thenComparing(ScriptMasterEntity::getScripCode))
                .limit(25).map(s -> new Instrument(s.getScripCode(), s.getTradingSymbol(), ex)).toList();
    }

    // Bound shared provider traffic and memory; cache only public instrument analysis, never account data.
    public Report analyze(String exchange, int scripCode, String mode) {
        if (!providerSlot.tryLock()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Market data is busy. Retry in a moment.");
        try { return analyzeLocked(exchange, scripCode, mode); }
        finally { providerSlot.unlock(); }
    }
    private Report analyzeLocked(String exchange, int scripCode, String mode) {
        String ex = exchange(exchange);
        if (!Set.of("INTRADAY", "SWING").contains(mode)) throw bad("Choose INTRADAY or SWING.");
        ScriptMasterEntity script = scripts.findByScripCode(scripCode);
        if (!cash(script) || !ex.equalsIgnoreCase(script.getExchange().trim()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cash instrument not found on the selected exchange.");
        Instant now = clock.instant(); String key = ex + ":" + scripCode + ":" + mode;
        cache.entrySet().removeIf(e -> !now.isBefore(e.getValue().until()));
        Cached old = cache.get(key);
        if (old != null) return old.report();
        long mono = System.nanoTime();
        if (lastFetchNanos != 0 && mono - lastFetchNanos < 500_000_000L)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Market data is busy. Retry in a moment.");
        lastFetchNanos = mono;
        String interval = mode.equals("INTRADAY") ? "5minute" : "daily";
        var instrument = new Instrument(scripCode, script.getTradingSymbol(), ex);
        List<HistoricalCandle> preferred = shoonya == null ? List.of() : shoonya.fetch(instrument, mode, now);
        Report report = calculate(instrument, mode, preferred, clock.instant(), "Shoonya", List.of());
        if (preferred.isEmpty() || report.dataStatus().equals("INCOMPLETE") || report.candles().isEmpty()) {
            List<HistoricalCandle> source = history.getRecentRawHistoricalCandles(scripCode, interval);
            Report fallback = calculate(instrument, mode, source == null ? List.of() : source, clock.instant(), "Sharekhan",
                    List.of("Shoonya shared feed unavailable or incomplete; showing separately validated Sharekhan history. Sources are never combined."));
            if (preferred.isEmpty() || report.candles().isEmpty() || (!fallback.candles().isEmpty() && !fallback.dataStatus().equals("INCOMPLETE"))) report = fallback;
        }
        if (cache.size() >= 64) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Cached(report.fetchedAt().plusSeconds(report.candles().isEmpty() ? 10 : 30), report));
        return report;
    }

    static Report calculate(Instrument instrument, String mode, List<HistoricalCandle> source, Instant fetchedAt) {
        return calculate(instrument, mode, source, fetchedAt, "Sharekhan", List.of());
    }
    static Report calculate(Instrument instrument, String mode, List<HistoricalCandle> source, Instant fetchedAt, String provider, List<String> notices) {
        boolean intraday = mode.equals("INTRADAY");
        LocalDateTime now = LocalDateTime.ofInstant(fetchedAt, IST);
        Map<LocalDateTime, Bar> unique = new TreeMap<>(); Set<LocalDateTime> conflicts = new HashSet<>();
        int rejected = 0, duplicates = 0, incomplete = 0, outside = 0, invalidVolume = 0;
        for (HistoricalCandle c : source) {
            if (c == null || c.date() == null || !valid(c)) { rejected++; continue; }
            LocalTime t = intraday ? c.time() : LocalTime.MIDNIGHT;
            if (t == null) { rejected++; continue; }
            if (intraday && t.getSecond() == 59 && t.getMinute() % 5 == 4) t = t.plusSeconds(1).minusMinutes(5);
            LocalDateTime stamp = c.date().atTime(t);
            if (intraday && (t.isBefore(LocalTime.of(9, 15)) || !t.isBefore(LocalTime.of(15, 30)))) { outside++; continue; }
            if (intraday && (t.getSecond() != 0 || t.getNano() != 0 || t.getMinute() % 5 != 0)) { rejected++; continue; }
            if (intraday ? stamp.plusMinutes(5).isAfter(now) : !c.date().isBefore(now.toLocalDate())) { incomplete++; continue; }
            Long volume = c.volume();
            if (volume != null && volume < 0) { invalidVolume++; volume = null; }
            Bar b = new Bar(c.date(), t, c.open(), c.high(), c.low(), c.close(), volume);
            Bar previous = unique.putIfAbsent(stamp, b);
            if (previous != null) { duplicates++; if (!previous.equals(b)) conflicts.add(stamp); }
        }
        conflicts.forEach(unique::remove);
        List<Bar> bars = new ArrayList<>(unique.values());
        // Analyze a bounded recent window. The endpoint is not an arbitrary-range archive.
        int max = intraday ? 1500 : 500;
        if (bars.size() > max) bars = new ArrayList<>(bars.subList(bars.size() - max, bars.size()));
        int gaps = 0;
        for (int i = 1; intraday && i < bars.size(); i++) {
            Bar a = bars.get(i - 1), b = bars.get(i);
            if (a.date().equals(b.date())) gaps += Math.max(0, (int) Duration.between(a.time(), b.time()).toMinutes() / 5 - 1);
        }
        List<String> warnings = new ArrayList<>(notices);
        warnings.add(provider + " returned history only; requested long-term coverage and corporate-action adjustment are not guaranteed.");
        warnings.add(intraday ? "Normal 09:15–15:30 IST cash session only. Special sessions are not modelled."
                : "Daily analysis excludes the current IST date, even after market close. Exchange holiday completeness is not inferred.");
        if (rejected > 0) warnings.add(rejected + " invalid bars removed; technical conclusions are withheld.");
        if (duplicates > 0) warnings.add(duplicates + " duplicate timestamps found; " + conflicts.size() + " conflicting timestamps removed.");
        if (gaps > 0) warnings.add(gaps + " missing five-minute intervals inside returned sessions; technical conclusions are withheld.");
        if (incomplete > 0) warnings.add(incomplete + " forming/future bars excluded.");
        if (outside > 0) warnings.add(outside + " bars outside the normal cash session excluded.");
        if (invalidVolume > 0) warnings.add(invalidVolume + " bars have negative provider volume, shown as unavailable. Price indicators remain usable; VWAP and volume confirmation are withheld where affected.");
        boolean clean = rejected == 0 && conflicts.isEmpty() && gaps == 0;
        Bar last = bars.isEmpty() ? null : bars.getLast();
        List<Bar> session = last == null ? List.of() : bars.stream().filter(b -> b.date().equals(last.date())).toList();
        Double sma20 = clean ? sma(bars, 20) : null, sma50 = clean ? sma(bars, 50) : null, sma200 = clean ? sma(bars, 200) : null;
        Double rsi = clean ? rsi(bars, 14) : null, atr = clean ? atr(bars, 14) : null;
        Double vwap = null, orHigh = null, orLow = null, prior = null;
        if (last != null) {
            prior = bars.stream().filter(b -> b.date().isBefore(last.date())).reduce((a, b) -> b).map(Bar::close).orElse(null);
            if (!intraday && bars.size() > 1) prior = bars.get(bars.size() - 2).close();
        }
        boolean startsAtOpen = !session.isEmpty() && session.getFirst().time().equals(LocalTime.of(9, 15));
        if (intraday && clean && startsAtOpen) {
            double total = 0, weighted = 0; boolean volumes = true;
            for (Bar b : session) {
                if (b.volume() == null || b.volume() < 0) { volumes = false; break; }
                total += b.volume(); weighted += ((b.high() + b.low() + b.close()) / 3) * b.volume();
            }
            if (volumes && total > 0 && Double.isFinite(weighted)) vwap = weighted / total;
            if (session.size() >= 3 && session.get(1).time().equals(LocalTime.of(9, 20)) && session.get(2).time().equals(LocalTime.of(9, 25))) {
                orHigh = session.subList(0, 3).stream().mapToDouble(Bar::high).max().orElseThrow();
                orLow = session.subList(0, 3).stream().mapToDouble(Bar::low).min().orElseThrow();
            }
        }
        String trend = sma50 == null ? "INSUFFICIENT_DATA" : last.close() > sma20 && sma20 > sma50 ? "BULLISH"
                : last.close() < sma20 && sma20 < sma50 ? "BEARISH" : "MIXED";
        LocalDateTime asOf = last == null ? null : intraday ? last.date().atTime(last.time()).plusMinutes(5)
                : last.date().atTime(15,30);
        String status = last == null ? "UNAVAILABLE" : !clean ? "INCOMPLETE" : intraday
                && Duration.between(asOf, now).toMinutes() <= 15 ? "RECENT" : "HISTORICAL";
        if (last == null) warnings.add("No usable completed candles. Market-data access may be unavailable, expired, unsupported or return no history; no opportunity conclusion can be drawn.");
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Data quality", clean && last != null ? "PASS" : "UNAVAILABLE", "Invalid prices, conflicting duplicates and intraday gaps block technical conclusions."));
        checks.add(new Check("Trend alignment", trend.equals("BULLISH") ? "PASS" : trend.equals("INSUFFICIENT_DATA") ? "UNAVAILABLE" : "WAIT",
                "Bullish research alignment requires close > SMA20 > SMA50. This is not a buy instruction."));
        Double rangeLow = null, rangeHigh = null;
        if (clean && bars.size() >= 21) {
            List<Bar> prior20 = bars.subList(bars.size() - 21, bars.size() - 1);
            rangeLow = prior20.stream().mapToDouble(Bar::low).min().orElseThrow();
            rangeHigh = prior20.stream().mapToDouble(Bar::high).max().orElseThrow();
        }
        if (!intraday) checks.add(new Check("20-bar price range", rangeHigh == null ? "UNAVAILABLE" : last.close() > rangeHigh ? "ABOVE" : last.close() < rangeLow ? "BELOW" : "INSIDE",
                "Last close versus the high/low of the preceding 20 available daily bars, excluding the last bar. Observed levels, not predicted targets."));
        if (intraday) {
            checks.add(new Check("15-minute opening range", orHigh == null ? "UNAVAILABLE" : last.close() > orHigh ? "ABOVE" : last.close() < orLow ? "BELOW" : "INSIDE",
                    "09:15–09:30 range of the latest returned session. All three opening bars are required."));
            checks.add(new Check("Session VWAP", vwap == null ? "UNAVAILABLE" : last.close() > vwap ? "ABOVE" : "BELOW",
                    "Typical-price × volume VWAP requires an uninterrupted session from 09:15 and known volume."));
            checks.add(new Check("Volume confirmation", volumeStatus(session, clean), "Latest bar volume versus the preceding 20 bars in this session; HIGH means at least 1.5× that average."));
            checks.add(new Check("Freshness", status.equals("RECENT") ? "PASS" : "HISTORICAL", "Latest completed bar must be within 15 minutes for recent-data status; this is not a live quote."));
        }
        checks.add(new Check("Automated strategy eligibility", "NOT_EVALUATED", "Research checks do not evaluate the full mover strategy, fresh-retest state, contract selection, account funds or execution guards."));
        return new Report(instrument, mode, intraday ? "5minute" : "daily", provider, "Asia/Kolkata", fetchedAt, asOf, status, trend,
                source.size(), rejected, duplicates, gaps, List.copyOf(bars), new Indicators(sma20, sma50, sma200, rsi, atr, vwap, orHigh, orLow, prior, rangeLow, rangeHigh), List.copyOf(checks), List.copyOf(warnings));
    }
    static boolean valid(HistoricalCandle c) {
        return Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low()) && Double.isFinite(c.close())
                && c.low() > 0 && c.high() <= 1e12 && c.low() <= Math.min(c.open(), c.close()) && c.high() >= Math.max(c.open(), c.close()) && c.high() >= c.low();
    }
    static Double sma(List<Bar> bars, int n) {
        if (bars.size() < n) return null;
        double result = bars.subList(bars.size() - n, bars.size()).stream().mapToDouble(Bar::close).average().orElseThrow();
        return Double.isFinite(result) ? result : null;
    }
    static Double rsi(List<Bar> bars, int n) {
        if (bars.size() <= n) return null;
        double up = 0, down = 0;
        for (int i = 1; i < bars.size(); i++) {
            double diff = bars.get(i).close() - bars.get(i - 1).close();
            if (i <= n) { up += Math.max(0, diff) / n; down += Math.max(0, -diff) / n; }
            else { up = (up * (n - 1) + Math.max(0, diff)) / n; down = (down * (n - 1) + Math.max(0, -diff)) / n; }
        }
        return up == 0 && down == 0 ? 50 : down == 0 ? 100 : 100 - 100 / (1 + up / down);
    }
    static Double atr(List<Bar> bars, int n) {
        if (bars.size() <= n) return null;
        double value = 0;
        for (int i = 1; i < bars.size(); i++) {
            Bar b = bars.get(i); double p = bars.get(i - 1).close();
            double tr = Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - p), Math.abs(b.low() - p)));
            value = i <= n ? value + tr / n : (value * (n - 1) + tr) / n;
        }
        return Double.isFinite(value) ? value : null;
    }
    static String volumeStatus(List<Bar> bars, boolean clean) {
        if (!clean || bars.size() < 21) return "UNAVAILABLE";
        List<Bar> window = bars.subList(bars.size() - 21, bars.size());
        if (window.stream().anyMatch(b -> b.volume() == null || b.volume() < 0)) return "UNAVAILABLE";
        double average = window.subList(0, 20).stream().mapToDouble(Bar::volume).average().orElse(0);
        return average <= 0 ? "UNAVAILABLE" : window.getLast().volume() >= average * 1.5 ? "HIGH" : "NORMAL";
    }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
