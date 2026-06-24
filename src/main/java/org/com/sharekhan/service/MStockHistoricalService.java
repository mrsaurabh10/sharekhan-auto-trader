package org.com.sharekhan.service;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockHistoricalService {

    private static final String HISTORICAL_URL = "https://api.mstock.trade/openapi/typea/instruments/historical";
    private static final DateTimeFormatter REQUEST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int MAX_CANDLES_PER_REQUEST = 1_000;

    private final TokenStoreService tokenStoreService;
    private final CryptoService cryptoService;
    private final MStockProperties mStockProperties;
    private final MStockInstrumentResolver instrumentResolver;
    private final MStockInstrumentRepository instrumentRepository;

    public HistoricalResponse getHistoricalCandles(Integer scripCode,
                                                   String exchange,
                                                   String instrument,
                                                   Double strikePrice,
                                                   String optionType,
                                                   String expiry,
                                                   String interval,
                                                   String from,
                                                   String to) {
        ScriptMasterEntity script = instrumentResolver
                .resolveScript(scripCode, exchange, instrument, strikePrice, optionType, expiry)
                .orElseThrow(() -> new IllegalArgumentException("Unable to locate script in cache."));
        String instrumentKey = instrumentResolver.resolveInstrumentKey(script)
                .orElseThrow(() -> new IllegalArgumentException("Unable to resolve MStock instrument key for provided script."));
        MStockInstrumentEntity mStockInstrument = resolveInstrumentMasterRow(instrumentKey)
                .orElseThrow(() -> new IllegalArgumentException("MStock instrument master row not found for key " + instrumentKey));
        return getHistoricalCandles(mStockInstrument, interval, parseFrom(from), parseTo(to));
    }

    public HistoricalResponse getHistoricalCandlesByToken(String exchange,
                                                          Long instrumentToken,
                                                          String interval,
                                                          String from,
                                                          String to) {
        if (!StringUtils.hasText(exchange)) {
            throw new IllegalArgumentException("exchange is required.");
        }
        if (instrumentToken == null || instrumentToken <= 0L) {
            throw new IllegalArgumentException("instrumentToken is required.");
        }
        MStockInstrumentEntity instrument = MStockInstrumentEntity.builder()
                .exchange(exchange.trim())
                .instrumentToken(instrumentToken)
                .instrumentKey(exchange.trim().toUpperCase() + ":" + instrumentToken)
                .tradingSymbol(String.valueOf(instrumentToken))
                .build();
        return getHistoricalCandles(instrument, interval, parseFrom(from), parseTo(to));
    }

    private Optional<MStockInstrumentEntity> resolveInstrumentMasterRow(String instrumentKey) {
        if (!StringUtils.hasText(instrumentKey)) {
            return Optional.empty();
        }
        Optional<MStockInstrumentEntity> exact = instrumentRepository.findByInstrumentKey(instrumentKey);
        if (exact.isPresent()) {
            return exact;
        }

        int colon = instrumentKey.indexOf(':');
        if (colon <= 0 || colon >= instrumentKey.length() - 1) {
            return Optional.empty();
        }

        String exchange = instrumentKey.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String symbol = instrumentKey.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
        for (String candidate : symbolCandidates(symbol)) {
            Optional<MStockInstrumentEntity> bySymbol = instrumentRepository.findByExchangeAndTradingSymbol(exchange, candidate);
            if (bySymbol.isPresent()) {
                log.info("Resolved MStock historical instrument key {} via tradingSymbol {}:{}",
                        instrumentKey, exchange, candidate);
                return bySymbol;
            }
        }
        return Optional.empty();
    }

    private List<String> symbolCandidates(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return List.of();
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, normalized);
        if (normalized.endsWith("-EQ")) {
            String withoutSeries = normalized.substring(0, normalized.length() - 3);
            addCandidate(candidates, withoutSeries);
            addCandidate(candidates, withoutSeries + "EQ");
        }
        if (normalized.endsWith("-A")) {
            String withoutSeries = normalized.substring(0, normalized.length() - 2);
            addCandidate(candidates, withoutSeries);
            addCandidate(candidates, withoutSeries + "A");
        }
        if (normalized.contains("-")) {
            addCandidate(candidates, normalized.replace("-", ""));
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String candidate) {
        if (StringUtils.hasText(candidate) && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private HistoricalResponse getHistoricalCandles(MStockInstrumentEntity instrument,
                                                    String interval,
                                                    LocalDateTime from,
                                                    LocalDateTime to) {
        if (from.isAfter(to)) {
            LocalDateTime previousFrom = from;
            from = to;
            to = previousFrom;
        }
        RequestCredentials credentials = resolveCredentials();
        String normalizedExchange = normalizeExchange(instrument.getExchange());
        String normalizedInterval = normalizeInterval(interval);
        return getHistoricalCandles(instrument, normalizedExchange, normalizedInterval, from, to, credentials);
    }

    private HistoricalResponse getHistoricalCandles(MStockInstrumentEntity instrument,
                                                    String normalizedExchange,
                                                    String normalizedInterval,
                                                    LocalDateTime from,
                                                    LocalDateTime to,
                                                    RequestCredentials credentials) {
        List<HistoricalResponse> responses = splitRanges(from, to, normalizedInterval).stream()
                .map(range -> getHistoricalCandlesSingleRequest(instrument, normalizedExchange, normalizedInterval,
                        range.from(), range.to(), credentials))
                .toList();

        Map<LocalDateTime, HistoricalCandle> candlesByTime = new LinkedHashMap<>();
        List<Map<String, Object>> rawResponses = new ArrayList<>();
        for (HistoricalResponse response : responses) {
            if (response.raw() != null) {
                rawResponses.add(response.raw());
            }
            if (response.candles() == null) {
                continue;
            }
            for (HistoricalCandle candle : response.candles()) {
                if (candle == null || candle.date() == null || candle.time() == null) {
                    continue;
                }
                candlesByTime.put(LocalDateTime.of(candle.date(), candle.time()), candle);
            }
        }
        List<HistoricalCandle> candles = candlesByTime.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("requestCount", responses.size());
        raw.put("responses", rawResponses);
        return HistoricalResponse.builder()
                .status("success")
                .exchange(normalizedExchange)
                .instrumentToken(instrument.getInstrumentToken())
                .instrumentKey(instrument.getInstrumentKey())
                .tradingSymbol(instrument.getTradingSymbol())
                .interval(normalizedInterval)
                .from(from)
                .to(to)
                .count(candles.size())
                .candles(candles)
                .raw(raw)
                .build();
    }

    private HistoricalResponse getHistoricalCandlesSingleRequest(MStockInstrumentEntity instrument,
                                                                 String normalizedExchange,
                                                                 String normalizedInterval,
                                                                 LocalDateTime from,
                                                                 LocalDateTime to,
                                                                 RequestCredentials credentials) {
        String url = HISTORICAL_URL + "/"
                + encodePath(normalizedExchange) + "/"
                + encodePath(String.valueOf(instrument.getInstrumentToken())) + "/"
                + encodePath(normalizedInterval)
                + "?from=" + encodeQuery(REQUEST_FORMAT.format(from))
                + "&to=" + encodeQuery(REQUEST_FORMAT.format(to));

        HttpResult result = doGet(url, credentials);
        if (result.code() == 401 || indicatesTokenException(result.body())) {
            TokenStoreService.TokenInfo refreshed = tokenStoreService.refreshToken(Broker.MSTOCK, credentials.tokenInfo());
            if (refreshed != null && StringUtils.hasText(refreshed.getToken())) {
                result = doGet(url, new RequestCredentials(refreshed.getToken(), resolveApiKey(refreshed), refreshed));
            }
        }
        if (result.code() < 200 || result.code() >= 300) {
            throw new MStockLtpException(result.code(), result.body());
        }

        JSONObject root = new JSONObject(result.body());
        if (!"success".equalsIgnoreCase(root.optString("status"))) {
            throw new IllegalStateException("MStock historical request failed: " + result.body());
        }
        JSONArray rows = root.optJSONObject("data") != null
                ? root.optJSONObject("data").optJSONArray("candles")
                : null;
        List<HistoricalCandle> candles = parseCandles(rows);
        return HistoricalResponse.builder()
                .status("success")
                .exchange(normalizedExchange)
                .instrumentToken(instrument.getInstrumentToken())
                .instrumentKey(instrument.getInstrumentKey())
                .tradingSymbol(instrument.getTradingSymbol())
                .interval(normalizedInterval)
                .from(from)
                .to(to)
                .count(candles.size())
                .candles(candles)
                .raw(root.toMap())
                .build();
    }

    private List<DateTimeRange> splitRanges(LocalDateTime from, LocalDateTime to, String interval) {
        if (estimatedCandles(from, to, interval) <= MAX_CANDLES_PER_REQUEST) {
            return List.of(new DateTimeRange(from, to));
        }
        List<DateTimeRange> ranges = new ArrayList<>();
        LocalDate date = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();
        while (!date.isAfter(endDate)) {
            LocalDateTime rangeFrom = date.equals(from.toLocalDate()) ? from : date.atTime(MARKET_OPEN);
            LocalDateTime rangeTo = date.equals(to.toLocalDate()) ? to : date.atTime(MARKET_CLOSE);
            if (!rangeFrom.isAfter(rangeTo)) {
                ranges.add(new DateTimeRange(rangeFrom, rangeTo));
            }
            date = date.plusDays(1);
        }
        return ranges;
    }

    private long estimatedCandles(LocalDateTime from, LocalDateTime to, String interval) {
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()) + 1);
        int minutes = intervalMinutes(interval);
        long candlesPerDay = Math.max(1, (java.time.Duration.between(MARKET_OPEN, MARKET_CLOSE).toMinutes() / minutes) + 1);
        return days * candlesPerDay;
    }

    private int intervalMinutes(String interval) {
        if (!StringUtils.hasText(interval) || "minute".equalsIgnoreCase(interval)) {
            return 1;
        }
        String digits = interval.trim().toLowerCase().replace("minute", "").replace("min", "");
        try {
            return Math.max(1, Integer.parseInt(digits));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private HttpResult doGet(String urlString, RequestCredentials credentials) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("X-Mirae-Version", "1");
            connection.setRequestProperty("Authorization", authHeader(credentials));

            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300
                            ? connection.getInputStream()
                            : (connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream()),
                    StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
            return new HttpResult(code, body.toString().trim());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to call MStock historical API: " + ex.getMessage(), ex);
        }
    }

    private List<HistoricalCandle> parseCandles(JSONArray rows) {
        List<HistoricalCandle> candles = new ArrayList<>();
        if (rows == null) {
            return candles;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 5) {
                continue;
            }
            LocalDateTime at = parseMStockDateTime(row.optString(0));
            if (at == null) {
                continue;
            }
            candles.add(HistoricalCandle.builder()
                    .date(at.toLocalDate())
                    .time(at.toLocalTime())
                    .open(row.optDouble(1))
                    .high(row.optDouble(2))
                    .low(row.optDouble(3))
                    .close(row.optDouble(4))
                    .volume(row.length() > 5 && !row.isNull(5) ? row.optLong(5) : null)
                    .build());
        }
        return candles;
    }

    private LocalDateTime parseFrom(String value) {
        return parseRequestDateTime(value, MARKET_OPEN);
    }

    private LocalDateTime parseTo(String value) {
        return parseRequestDateTime(value, MARKET_CLOSE);
    }

    private LocalDateTime parseRequestDateTime(String value, LocalTime defaultTime) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("from and to are required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() == 10) {
            return LocalDate.parse(trimmed).atTime(defaultTime);
        }
        return LocalDateTime.parse(trimmed.replace(' ', 'T'));
    }

    private LocalDateTime parseMStockDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        try {
            return OffsetDateTime.parse(normalized).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(normalized.replace(' ', 'T'));
        }
    }

    private String normalizeInterval(String interval) {
        String value = StringUtils.hasText(interval) ? interval.trim().toLowerCase() : "minute";
        if ("1minute".equals(value) || "1min".equals(value) || "1".equals(value)) {
            return "minute";
        }
        if (value.endsWith("min")) {
            return value.substring(0, value.indexOf("min")) + "minute";
        }
        return value;
    }

    private String normalizeExchange(String exchange) {
        if (!StringUtils.hasText(exchange)) {
            throw new IllegalArgumentException("MStock exchange is missing for resolved instrument.");
        }
        String value = exchange.trim().toUpperCase();
        if ("NF".equals(value) || "NSE_FNO".equals(value)) {
            return "NFO";
        }
        return value;
    }

    private RequestCredentials resolveCredentials() {
        TokenStoreService.TokenInfo tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        String token = tokenInfo != null ? tokenInfo.getToken() : null;
        if (!StringUtils.hasText(token)) {
            token = tokenStoreService.getAccessToken(Broker.MSTOCK);
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }
        return new RequestCredentials(token, resolveApiKey(tokenInfo), tokenInfo);
    }

    private String resolveApiKey(TokenStoreService.TokenInfo tokenInfo) {
        String apiKey = mStockProperties.getApiKey();
        if (tokenInfo != null && StringUtils.hasText(tokenInfo.getApiKey())) {
            try {
                apiKey = cryptoService.decrypt(tokenInfo.getApiKey());
            } catch (Exception ex) {
                apiKey = tokenInfo.getApiKey();
            }
        }
        return apiKey;
    }

    private String authHeader(RequestCredentials credentials) {
        return StringUtils.hasText(credentials.apiKey())
                ? "token " + credentials.apiKey() + ":" + credentials.accessToken()
                : "token " + credentials.accessToken();
    }

    private boolean indicatesTokenException(String body) {
        try {
            if (!StringUtils.hasText(body)) {
                return false;
            }
            JSONObject root = new JSONObject(body);
            return "TokenException".equalsIgnoreCase(root.optString("error_type"));
        } catch (Exception ex) {
            return false;
        }
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record RequestCredentials(String accessToken, String apiKey, TokenStoreService.TokenInfo tokenInfo) {
    }

    private record HttpResult(int code, String body) {
    }

    private record DateTimeRange(LocalDateTime from, LocalDateTime to) {
    }

    @Builder
    public record HistoricalResponse(String status,
                                     String exchange,
                                     Long instrumentToken,
                                     String instrumentKey,
                                     String tradingSymbol,
                                     String interval,
                                     LocalDateTime from,
                                     LocalDateTime to,
                                     Integer count,
                                     List<HistoricalCandle> candles,
                                     Map<String, Object> raw) {
    }

    @Builder
    public record HistoricalCandle(LocalDate date,
                                   LocalTime time,
                                   double open,
                                   double high,
                                   double low,
                                   double close,
                                   Long volume) {
    }
}
