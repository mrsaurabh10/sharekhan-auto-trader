package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockIntradayCandleService {

    private static final String INTRADAY_URL_TEMPLATE =
            "https://api.mstock.trade/openapi/typea/instruments/intraday/%s/%s/%s";
    private static final long MISSING_CANDLE_RETRY_NANOS = 2_000_000_000L;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Map<String, String> EXCHANGE_SEGMENTS = Map.ofEntries(
            Map.entry("1", "1"),
            Map.entry("NSE", "1"),
            Map.entry("NC", "1"),
            Map.entry("2", "2"),
            Map.entry("NFO", "2"),
            Map.entry("NF", "2"),
            Map.entry("3", "3"),
            Map.entry("CDS", "3"),
            Map.entry("4", "4"),
            Map.entry("BSE", "4"),
            Map.entry("BC", "4"),
            Map.entry("5", "5"),
            Map.entry("BFO", "5"),
            Map.entry("BF", "5")
    );

    private final TokenStoreService tokenStoreService;
    private final CryptoService cryptoService;
    private final MStockInstrumentResolver instrumentResolver;
    private final MStockInstrumentRepository instrumentRepository;
    private final ConcurrentMap<CompletedMinuteKey, IntradayCandle> completedMinuteCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<CompletedMinuteKey, Long> completedMinuteAttempts = new ConcurrentHashMap<>();

    @Value("${app.mstock.api-key:}")
    private String apiKey;

    public List<IntradayCandle> getIntradayCandles(String exchange, String symbolToken, String interval) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(symbolToken) || !StringUtils.hasText(interval)) {
            return List.of();
        }

        String normalizedExchange = normalizeExchangeSegment(exchange);
        String normalizedSymbolToken = symbolToken.trim();
        String normalizedInterval = interval.trim();
        String url = String.format(INTRADAY_URL_TEMPLATE, normalizedExchange, normalizedSymbolToken, normalizedInterval);
        TokenStoreService.TokenInfo tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        String accessToken = null;
        String effectiveApiKey = this.apiKey;
        if (tokenInfo != null) {
            accessToken = tokenInfo.getToken();
            if (StringUtils.hasText(tokenInfo.getApiKey())) {
                effectiveApiKey = decryptIfNeeded(tokenInfo.getApiKey());
            }
        }
        if (!StringUtils.hasText(accessToken)) {
            accessToken = tokenStoreService.getAccessToken(Broker.MSTOCK);
        }
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }

        HttpResult result = doRequest(url, accessToken, effectiveApiKey);
        if (result.code == 401 || indicatesTokenException(result.body)) {
            TokenStoreService.TokenInfo refreshed = tokenStoreService.refreshToken(Broker.MSTOCK, tokenInfo);
            if (refreshed != null && StringUtils.hasText(refreshed.getToken())) {
                accessToken = refreshed.getToken();
                if (StringUtils.hasText(refreshed.getApiKey())) {
                    effectiveApiKey = decryptIfNeeded(refreshed.getApiKey());
                }
                result = doRequest(url, accessToken, effectiveApiKey);
            }
        }

        if (result.code != 200) {
            throw new RuntimeException("MStock intraday request failed (http:" + result.code + "): " + result.body);
        }

        JSONObject root = new JSONObject(result.body);
        if (!"success".equalsIgnoreCase(root.optString("status", ""))) {
            throw new RuntimeException("MStock intraday request failed: " + result.body);
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray candles = data != null ? data.optJSONArray("candles") : null;
        if (candles == null || candles.isEmpty()) {
            log.warn("MStock intraday response contained no candles exchange={}, symbolToken={}, interval={}",
                    normalizedExchange, normalizedSymbolToken, normalizedInterval);
            return List.of();
        }

        List<IntradayCandle> parsed = new ArrayList<>();
        for (int i = 0; i < candles.length(); i++) {
            IntradayCandle candle = parseCandle(candles.optJSONArray(i));
            if (candle != null && candle.hasOhlc()) {
                parsed.add(candle);
            }
        }
        if (parsed.isEmpty()) {
            log.warn("MStock intraday response had {} rows but zero valid OHLC candles exchange={}, symbolToken={}, interval={}",
                    candles.length(), normalizedExchange, normalizedSymbolToken, normalizedInterval);
        }
        parsed.sort(Comparator.comparing(IntradayCandle::date).thenComparing(IntradayCandle::time));
        logLatestCandles(normalizedExchange, normalizedSymbolToken, normalizedInterval, parsed);
        return parsed;
    }

    private void logLatestCandles(String exchange,
                                  String symbolToken,
                                  String interval,
                                  List<IntradayCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }
        IntradayCandle latest = candles.get(candles.size() - 1);
        IntradayCandle previous = candles.size() > 1 ? candles.get(candles.size() - 2) : null;
        log.info("MStock intraday candles exchange={} symbolToken={} interval={} latest={} previous={}",
                exchange, symbolToken, interval, candleSummary(latest), candleSummary(previous));
    }

    static String candleSummary(IntradayCandle candle) {
        if (candle == null) {
            return "unavailable";
        }
        String volume = candle.volume() != null ? candle.volume().toString() : "unavailable";
        return String.format(Locale.ROOT,
                "[%sT%s O=%s H=%s L=%s C=%s V=%s]",
                candle.date(), candle.time(),
                candle.open(), candle.high(), candle.low(), candle.close(), volume);
    }

    /**
     * Resolve a Sharekhan spot scrip to its MStock exchange token and return the exact completed
     * one-minute candle requested. The intraday API uses the exchange token, not the Sharekhan
     * scrip code or MStock instrument token.
     */
    public IntradayCandle getCompletedMinuteCandle(Integer spotScripCode, LocalDateTime minute) {
        if (spotScripCode == null || minute == null) {
            return null;
        }
        LocalDateTime normalizedMinute = minute.withSecond(0).withNano(0);
        CompletedMinuteKey cacheKey = new CompletedMinuteKey(spotScripCode, normalizedMinute);
        IntradayCandle cached = completedMinuteCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long nowNanos = System.nanoTime();
        Long previousAttempt = completedMinuteAttempts.get(cacheKey);
        if (previousAttempt != null && nowNanos - previousAttempt < MISSING_CANDLE_RETRY_NANOS) {
            return null;
        }
        completedMinuteAttempts.put(cacheKey, nowNanos);
        String instrumentKey = instrumentResolver.resolveInstrumentKey(spotScripCode).orElse(null);
        if (!StringUtils.hasText(instrumentKey)) {
            log.warn("Unable to resolve MStock instrument key for spot scripCode={}", spotScripCode);
            return null;
        }
        MStockInstrumentEntity instrument = instrumentRepository.findByInstrumentKey(instrumentKey).orElse(null);
        String keyExchange = instrumentKey.contains(":")
                ? instrumentKey.substring(0, instrumentKey.indexOf(':'))
                : null;
        String exchange = instrument != null && StringUtils.hasText(instrument.getExchange())
                ? instrument.getExchange()
                : keyExchange;
        String exchangeToken = instrument != null ? instrument.getExchangeToken() : null;
        if (!StringUtils.hasText(exchangeToken) && isCashExchange(exchange) && spotScripCode > 0) {
            // Sharekhan cash scrip codes are the native NSE/BSE exchange tokens. This also keeps
            // live confirmation working while a legacy MStock master is being refreshed.
            exchangeToken = spotScripCode.toString();
            log.info("Using native cash exchange token fallback for spot scripCode={}, instrumentKey={}",
                    spotScripCode, instrumentKey);
        }
        if (!StringUtils.hasText(exchangeToken)) {
            log.warn("MStock exchange token unavailable for spot scripCode={}, instrumentKey={}, masterRowPresent={}",
                    spotScripCode, instrumentKey, instrument != null);
            return null;
        }
        LocalDate expectedDate = normalizedMinute.toLocalDate();
        LocalTime expectedTime = normalizedMinute.toLocalTime();
        IntradayCandle resolved = getIntradayCandles(exchange, exchangeToken, "minute").stream()
                .filter(candle -> candle.date().equals(expectedDate) && candle.time().equals(expectedTime))
                .findFirst()
                .orElse(null);
        if (resolved != null) {
            completedMinuteCache.put(cacheKey, resolved);
            completedMinuteAttempts.remove(cacheKey);
            completedMinuteCache.keySet().removeIf(key -> key.minute().toLocalDate().isBefore(expectedDate));
            completedMinuteAttempts.keySet().removeIf(key -> key.minute().toLocalDate().isBefore(expectedDate));
        }
        return resolved;
    }

    /**
     * Returns only completed five-minute candles available at {@code now}.  The
     * current five-minute bar is deliberately excluded: using it for a breakout
     * decision makes an intrabar spike look like a confirmed close.
     */
    public List<IntradayCandle> getCompletedFiveMinuteCandles(Integer spotScripCode, LocalDateTime now) {
        if (spotScripCode == null || now == null) {
            return List.of();
        }
        String instrumentKey = instrumentResolver.resolveInstrumentKey(spotScripCode).orElse(null);
        if (!StringUtils.hasText(instrumentKey)) {
            return List.of();
        }
        MStockInstrumentEntity instrument = instrumentRepository.findByInstrumentKey(instrumentKey).orElse(null);
        String keyExchange = instrumentKey.contains(":") ? instrumentKey.substring(0, instrumentKey.indexOf(':')) : null;
        String exchange = instrument != null && StringUtils.hasText(instrument.getExchange()) ? instrument.getExchange() : keyExchange;
        String exchangeToken = instrument != null ? instrument.getExchangeToken() : null;
        if (!StringUtils.hasText(exchangeToken) && isCashExchange(exchange) && spotScripCode > 0) {
            exchangeToken = spotScripCode.toString();
        }
        if (!StringUtils.hasText(exchangeToken)) {
            return List.of();
        }
        LocalTime currentBarStart = now.toLocalTime().withSecond(0).withNano(0)
                .minusMinutes(now.getMinute() % 5);
        return getIntradayCandles(exchange, exchangeToken, "5minute").stream()
                .filter(candle -> candle.date().equals(now.toLocalDate()))
                .filter(candle -> candle.time().isBefore(currentBarStart))
                .toList();
    }

    private boolean isCashExchange(String exchange) {
        return "NSE".equalsIgnoreCase(exchange)
                || "NC".equalsIgnoreCase(exchange)
                || "BSE".equalsIgnoreCase(exchange)
                || "BC".equalsIgnoreCase(exchange);
    }

    private IntradayCandle parseCandle(JSONArray row) {
        if (row == null || row.length() < 5) {
            return null;
        }
        LocalDateTime timestamp = parseTimestamp(row.optString(0, null));
        if (timestamp == null) {
            return null;
        }
        Long volume = row.length() > 5 && !row.isNull(5) ? row.optLong(5) : null;
        return new IntradayCandle(
                timestamp.toLocalDate(),
                timestamp.toLocalTime(),
                row.optDouble(1, Double.NaN),
                row.optDouble(2, Double.NaN),
                row.optDouble(3, Double.NaN),
                row.optDouble(4, Double.NaN),
                volume
        );
    }

    private LocalDateTime parseTimestamp(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        int tIndex = text.indexOf('T');
        int offsetIndex = tIndex >= 0 ? Math.max(text.indexOf('+', tIndex), text.indexOf('-', tIndex + 1)) : -1;
        if (offsetIndex > 0) {
            try {
                return LocalDateTime.parse(text.substring(0, offsetIndex), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {
            }
        }
        if (text.matches(".*[+-]\\d{2}$")) {
            text = text + ":00";
        } else if (text.matches(".*[+-]\\d{4}$")) {
            text = text.substring(0, text.length() - 2) + ":" + text.substring(text.length() - 2);
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(MARKET_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private HttpResult doRequest(String urlStr, String accessToken, String apiKey) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("X-Mirae-Version", "1");
            conn.setRequestProperty("Authorization", authorizationValue(accessToken, apiKey));

            int rc = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    rc >= 200 && rc < 300
                            ? conn.getInputStream()
                            : (conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()),
                    StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
            return new HttpResult(rc, body.toString().trim());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch MStock intraday candles: " + e.getMessage(), e);
        }
    }

    private String authorizationValue(String accessToken, String apiKey) {
        if (StringUtils.hasText(apiKey)) {
            return "token " + apiKey + ":" + accessToken;
        }
        return "token " + accessToken;
    }

    static String normalizeExchangeSegment(String exchange) {
        if (!StringUtils.hasText(exchange)) {
            return null;
        }
        String trimmed = exchange.trim().toUpperCase(Locale.ROOT);
        String segment = EXCHANGE_SEGMENTS.get(trimmed);
        if (!StringUtils.hasText(segment)) {
            throw new IllegalArgumentException("Unsupported MStock exchange segment: " + exchange);
        }
        return segment;
    }

    private String decryptIfNeeded(String value) {
        try {
            return cryptoService.decrypt(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean indicatesTokenException(String body) {
        try {
            if (!StringUtils.hasText(body)) {
                return false;
            }
            JSONObject root = new JSONObject(body);
            return "TokenException".equalsIgnoreCase(root.optString("error_type", null)) || root.has("Error");
        } catch (Exception ignored) {
            return false;
        }
    }

    private record HttpResult(int code, String body) {
    }

    private record CompletedMinuteKey(Integer spotScripCode, LocalDateTime minute) {
    }

    public record IntradayCandle(LocalDate date,
                                 LocalTime time,
                                 double open,
                                 double high,
                                 double low,
                                 double close,
                                 Long volume) {
        public boolean hasOhlc() {
            return Double.isFinite(open)
                    && Double.isFinite(high)
                    && Double.isFinite(low)
                    && Double.isFinite(close);
        }

        public boolean hasVolume() {
            return volume != null && volume > 0L;
        }
    }
}
