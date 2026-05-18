package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.auth.TokenStoreService.TokenInfo;
import org.com.sharekhan.config.SharekhanProperties;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.util.SharekhanConsoleSilencer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SharekhanHistoricalService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String DEFAULT_INTERVAL_SEGMENT = "15minute";
    private static final String DEFAULT_QUERY_TEMPLATE = "from=%s&to=%s";
    private static final LocalTime TARGET_OPEN_TIME = LocalTime.of(9, 20);
    private static final List<DateTimeFormatter> DATE_FORMATS;

    static {
        List<DateTimeFormatter> formats = new ArrayList<>();
        formats.add(DateTimeFormatter.ISO_DATE);
        formats.add(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        formats.add(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        formats.add(DateTimeFormatter.ofPattern("d-M-yyyy"));
        formats.add(DateTimeFormatter.ofPattern("d/M/yyyy"));
        formats.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        DATE_FORMATS = List.copyOf(formats);
    }

    private final ScriptMasterRepository scriptMasterRepository;
    private final TokenStoreService tokenStoreService;
    private final CryptoService cryptoService;
    private final SharekhanProperties sharekhanProperties;

    // Cache opening price per trading day (IST) and scrip code
    private final Map<LocalDate, Map<Integer, Double>> openPriceCache = new ConcurrentHashMap<>();

    /**
     * Fetch the opening price for the provided scrip code for the current trading day (IST).
     * Results are cached for the day to avoid repeated Historical API calls.
     */
    public OptionalDouble getTodayOpenPrice(Integer scripCode) {
        if (scripCode == null) {
            return OptionalDouble.empty();
        }

        LocalDate today = LocalDate.now(IST_ZONE);
        // Drop stale cache entries
        purgeOlderThan(today);

        Map<Integer, Double> dayCache = openPriceCache.computeIfAbsent(today, d -> new ConcurrentHashMap<>());
        Double cached = dayCache.get(scripCode);
        if (cached != null) {
            return OptionalDouble.of(cached);
        }

        OptionalDouble fetched = fetchOpenPrice(scripCode, today);
        fetched.ifPresent(value -> dayCache.put(scripCode, value));
        return fetched;
    }

    public List<HistoricalCandle> getHistoricalCandles(Integer scripCode,
                                                       String intervalSegment,
                                                       LocalDate from,
                                                       LocalDate to) {
        if (scripCode == null) {
            return List.of();
        }

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (script == null || !StringUtils.hasText(script.getExchange())) {
            log.debug("Sharekhan historical candles skipped: script {} not found in master cache", scripCode);
            return List.of();
        }

        String exchange = script.getExchange().trim().toUpperCase();
        String accessToken = resolveAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            log.debug("Sharekhan historical candles skipped: access token unavailable");
            return List.of();
        }

        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            log.debug("Sharekhan historical candles skipped: API key unavailable");
            return List.of();
        }

        try {
            String intervalPath = buildIntervalPath(intervalSegment, from, to);
            SharekhanConnect client = new SharekhanConnect(null, apiKey, accessToken);
            JSONObject response = SharekhanConsoleSilencer.call(() ->
                    client.getHistorical(exchange, String.valueOf(scripCode), intervalPath));
            return parseHistoricalCandles(response);
        } catch (Exception ex) {
            log.warn("Failed to fetch Sharekhan historical candles for scrip {}: {}", scripCode, ex.getMessage());
            log.debug("Historical candle fetch error", ex);
            return List.of();
        }
    }

    private OptionalDouble fetchOpenPrice(Integer scripCode, LocalDate targetDate) {
        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (script == null || !StringUtils.hasText(script.getExchange())) {
            log.debug("Sharekhan historical open price skipped: script {} not found in master cache", scripCode);
            return OptionalDouble.empty();
        }

        String exchange = script.getExchange().trim().toUpperCase();

        String accessToken = resolveAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            log.debug("Sharekhan historical open price skipped: access token unavailable");
            return OptionalDouble.empty();
        }

        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            log.debug("Sharekhan historical open price skipped: API key unavailable");
            return OptionalDouble.empty();
        }

        try {
            String intervalPath = buildIntervalPath(targetDate);
            SharekhanConnect client = new SharekhanConnect(null, apiKey, accessToken);
            JSONObject response = SharekhanConsoleSilencer.call(() ->
                    client.getHistorical(exchange, String.valueOf(scripCode), intervalPath));
            OptionalDouble parsed = parseOpenPrice(response, targetDate);
            parsed.ifPresent(value -> log.debug("Sharekhan historical open price fetched: scrip={} exchange={} open={} date={}",
                    scripCode, exchange, value, targetDate));
            return parsed;
        } catch (Exception ex) {
            log.warn("Failed to fetch Sharekhan historical open price for scrip {}: {}", scripCode, ex.getMessage());
            log.debug("Historical fetch error", ex);
            return OptionalDouble.empty();
        }
    }

    private String resolveAccessToken() {
        TokenInfo tokenInfo = null;
        try {
            tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN);
        } catch (Exception ex) {
            log.warn("Unable to resolve Sharekhan token info for historical fetch: {}", ex.getMessage());
        }

        String accessToken = tokenInfo != null ? tokenInfo.getToken() : null;
        if (!StringUtils.hasText(accessToken)) {
            accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
        }
        return accessToken;
    }

    private String resolveApiKey() {
        String apiKeyCandidate = null;
        try {
            TokenInfo tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN);
            apiKeyCandidate = tokenInfo != null ? tokenInfo.getApiKey() : null;
        } catch (Exception ignored) {
        }
        if (!StringUtils.hasText(apiKeyCandidate)) {
            apiKeyCandidate = sharekhanProperties.getApiKey();
        }
        return decryptIfNeeded(apiKeyCandidate);
    }

    private OptionalDouble parseOpenPrice(JSONObject response, LocalDate targetDate) {
        if (response == null) {
            return OptionalDouble.empty();
        }

        JSONArray candles = extractCandlesArray(response);
        if (candles == null || candles.isEmpty()) {
            return OptionalDouble.empty();
        }

        Double fallbackOpen = null;
        Double targetTimeOpen = null;
        Double firstAfterTarget = null;
        Double firstForDay = null;
        for (int i = 0; i < candles.length(); i++) {
            Object item = candles.get(i);
            Candle candle = parseCandle(item);
            if (candle == null || Double.isNaN(candle.open())) {
                continue;
            }
            fallbackOpen = candle.open();
            if (candle.date() == null || targetDate == null) {
                continue;
            }
            if (!targetDate.equals(candle.date())) {
                continue;
            }

            if (firstForDay == null) {
                firstForDay = candle.open();
            }

            if (candle.time() != null) {
                if (TARGET_OPEN_TIME.equals(candle.time())) {
                    targetTimeOpen = candle.open();
                    break;
                } else if (candle.time().isAfter(TARGET_OPEN_TIME) && firstAfterTarget == null) {
                    firstAfterTarget = candle.open();
                }
            }
        }

        if (targetTimeOpen != null) {
            return OptionalDouble.of(targetTimeOpen);
        }
        if (firstAfterTarget != null) {
            log.debug("Sharekhan historical open price at 09:20 not found. Using first candle after 09:20 with open {}", firstAfterTarget);
            return OptionalDouble.of(firstAfterTarget);
        }
        if (firstForDay != null) {
            log.debug("Sharekhan historical 5-minute data missing 09:20 candle. Using first candle for day with open {}", firstForDay);
            return OptionalDouble.of(firstForDay);
        }
        if (fallbackOpen != null) {
            log.debug("Sharekhan historical data missing entries for target date {}. Using latest candle open {}", targetDate, fallbackOpen);
            return OptionalDouble.of(fallbackOpen);
        }
        return OptionalDouble.empty();
    }

    private JSONArray extractCandlesArray(JSONObject response) {
        if (response == null) {
            return null;
        }

        Object dataNode = response.opt("data");
        if (dataNode instanceof JSONObject dataObj) {
            JSONArray exact = dataObj.optJSONArray("candles");
            if (exact != null) {
                return exact;
            }
            // Fallback: return the first JSONArray child
            for (String key : dataObj.keySet()) {
                Object val = dataObj.opt(key);
                if (val instanceof JSONArray arr) {
                    return arr;
                }
            }
        } else if (dataNode instanceof JSONArray arr) {
            return arr;
        }

        // Last resort: search top-level JSON for any array field
        Set<String> keys = response.keySet();
        for (String key : keys) {
            Object val = response.opt(key);
            if (val instanceof JSONArray arr) {
                return arr;
            }
            if (val instanceof JSONObject obj) {
                JSONArray nested = extractCandlesArray(obj);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private Candle parseCandle(Object node) {
        if (node instanceof JSONArray array) {
            if (array.length() < 2) {
                return null;
            }
            double open = array.optDouble(1, Double.NaN);
            String ts = array.optString(0, null);
            LocalDate date = extractDate(ts);
            LocalTime time = extractTime(ts);
            return new Candle(date, time, open);
        }
        if (node instanceof JSONObject obj) {
            double open = extractOpenValue(obj);
            String dateString = extractDateString(obj);
            LocalDate date = extractDate(dateString);
            LocalTime time = extractTime(dateString != null ? dateString : obj.optString("tradeTime", null));
            if (time == null && obj.has("tradeTime")) {
                time = extractTime(obj.optString("tradeTime", null));
            }
            return new Candle(date, time, open);
        }
        return null;
    }

    private List<HistoricalCandle> parseHistoricalCandles(JSONObject response) {
        JSONArray candles = extractCandlesArray(response);
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        List<HistoricalCandle> parsed = new ArrayList<>();
        for (int i = 0; i < candles.length(); i++) {
            HistoricalCandle candle = parseHistoricalCandle(candles.get(i));
            if (candle != null && candle.hasOhlc()) {
                parsed.add(candle);
            }
        }
        return parsed;
    }

    private HistoricalCandle parseHistoricalCandle(Object node) {
        if (node instanceof JSONArray array) {
            if (array.length() < 5) {
                return null;
            }
            String ts = array.optString(0, null);
            return new HistoricalCandle(
                    extractDate(ts),
                    extractTime(ts),
                    array.optDouble(1, Double.NaN),
                    array.optDouble(2, Double.NaN),
                    array.optDouble(3, Double.NaN),
                    array.optDouble(4, Double.NaN)
            );
        }
        if (node instanceof JSONObject obj) {
            String dateString = extractDateString(obj);
            LocalTime time = extractTime(dateString != null ? dateString : obj.optString("tradeTime", null));
            if (time == null && obj.has("tradeTime")) {
                time = extractTime(obj.optString("tradeTime", null));
            }
            return new HistoricalCandle(
                    extractDate(dateString),
                    time,
                    extractOpenValue(obj),
                    extractPriceValue(obj, "high", "High", "h"),
                    extractPriceValue(obj, "low", "Low", "l"),
                    extractPriceValue(obj, "close", "Close", "c")
            );
        }
        return null;
    }

    private double extractOpenValue(JSONObject obj) {
        if (obj == null) {
            return Double.NaN;
        }
        double value = tryDouble(obj, "open");
        if (!Double.isNaN(value)) return value;
        value = tryDouble(obj, "Open");
        if (!Double.isNaN(value)) return value;
        value = tryDouble(obj, "o");
        return value;
    }

    private double tryDouble(JSONObject obj, String key) {
        if (!obj.has(key)) {
            return Double.NaN;
        }
        Object val = obj.opt(key);
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        if (val instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private double extractPriceValue(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return Double.NaN;
        }
        for (String key : keys) {
            double value = tryDouble(obj, key);
            if (!Double.isNaN(value)) {
                return value;
            }
        }
        return Double.NaN;
    }

    private String extractDateString(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        for (String key : List.of("time", "date", "Date", "tradeDate", "TradeDate", "timestamp", "Timestamp", "datetime", "Datetime")) {
            if (obj.has(key)) {
                Object value = obj.opt(key);
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }

    private LocalDate extractDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        String sanitized = raw.trim();
        int tIndex = sanitized.indexOf('T');
        if (tIndex > 0) {
            sanitized = sanitized.substring(0, tIndex);
        }
        int spaceIndex = sanitized.indexOf(' ');
        if (spaceIndex > 0) {
            sanitized = sanitized.substring(0, spaceIndex);
        }
        int plusIndex = sanitized.indexOf('+');
        if (plusIndex > 0) {
            sanitized = sanitized.substring(0, plusIndex);
        }

        sanitized = sanitized.replace('/', '-');

        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(sanitized, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private LocalTime extractTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String sanitized = raw.trim();
        int spaceIndex = sanitized.indexOf(' ');
        if (spaceIndex >= 0) {
            sanitized = sanitized.substring(spaceIndex + 1);
        }
        int tIndex = sanitized.indexOf('T');
        if (tIndex >= 0) {
            sanitized = sanitized.substring(tIndex + 1);
        }
        int plusIndex = sanitized.indexOf('+');
        if (plusIndex > 0) {
            sanitized = sanitized.substring(0, plusIndex);
        }
        if (sanitized.length() == 0) {
            return null;
        }
        // Accept formats like HH:mm:ss or HH:mm
        try {
            if (sanitized.length() == 5) {
                return LocalTime.parse(sanitized + ":00");
            }
            return LocalTime.parse(sanitized);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String decryptIfNeeded(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        try {
            return cryptoService.decrypt(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private void purgeOlderThan(LocalDate reference) {
        openPriceCache.keySet().removeIf(date -> date.isBefore(reference));
    }

    private String buildIntervalPath(LocalDate targetDate) {
        return buildIntervalPath(DEFAULT_INTERVAL_SEGMENT, targetDate, targetDate);
    }

    private record Candle(LocalDate date, LocalTime time, double open) { }

    private String buildIntervalPath(String intervalSegment, LocalDate from, LocalDate to) {
        String interval = StringUtils.hasText(intervalSegment) ? intervalSegment.trim() : DEFAULT_INTERVAL_SEGMENT;
        if (from != null && to != null && StringUtils.hasText(DEFAULT_QUERY_TEMPLATE)) {
            String query = String.format(DEFAULT_QUERY_TEMPLATE,
                    from.format(DateTimeFormatter.ISO_DATE),
                    to.format(DateTimeFormatter.ISO_DATE));
            interval = interval + "?" + query;
        }
        return interval;
    }

    public record HistoricalCandle(LocalDate date,
                                   LocalTime time,
                                   double open,
                                   double high,
                                   double low,
                                   double close) {
        private boolean hasOhlc() {
            return Double.isFinite(open)
                    && Double.isFinite(high)
                    && Double.isFinite(low)
                    && Double.isFinite(close);
        }
    }
}
