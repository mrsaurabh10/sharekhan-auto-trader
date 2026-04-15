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

    private OptionalDouble fetchOpenPrice(Integer scripCode, LocalDate targetDate) {
        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (script == null || !StringUtils.hasText(script.getExchange())) {
            log.debug("Sharekhan historical open price skipped: script {} not found in master cache", scripCode);
            return OptionalDouble.empty();
        }

        String exchange = script.getExchange().trim().toUpperCase();

        TokenInfo tokenInfo = null;
        try {
            tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN);
        } catch (Exception ex) {
            log.warn("Unable to resolve Sharekhan token info for historical price fetch: {}", ex.getMessage());
        }

        String accessToken = tokenInfo != null ? tokenInfo.getToken() : null;
        if (!StringUtils.hasText(accessToken)) {
            accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
        }
        if (!StringUtils.hasText(accessToken)) {
            log.debug("Sharekhan historical open price skipped: access token unavailable");
            return OptionalDouble.empty();
        }

        String apiKeyCandidate = tokenInfo != null ? tokenInfo.getApiKey() : sharekhanProperties.getApiKey();
        String apiKey = decryptIfNeeded(apiKeyCandidate);
        if (!StringUtils.hasText(apiKey)) {
            log.debug("Sharekhan historical open price skipped: API key unavailable");
            return OptionalDouble.empty();
        }

        try {
            String intervalPath = buildIntervalPath(targetDate);
            SharekhanConnect client = new SharekhanConnect(null, apiKey, accessToken);
            JSONObject response = client.getHistorical(exchange, String.valueOf(scripCode), intervalPath);
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
        String interval = DEFAULT_INTERVAL_SEGMENT;
        if (targetDate != null && StringUtils.hasText(DEFAULT_QUERY_TEMPLATE)) {
            String dateStr = targetDate.format(DateTimeFormatter.ISO_DATE);
            String query = String.format(DEFAULT_QUERY_TEMPLATE, dateStr, dateStr);
            interval = interval + "?" + query;
        }
        return interval;
    }

    private record Candle(LocalDate date, LocalTime time, double open) { }
}
