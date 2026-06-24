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
        MStockInstrumentEntity mStockInstrument = instrumentRepository.findByInstrumentKey(instrumentKey)
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
                credentials = new RequestCredentials(refreshed.getToken(), resolveApiKey(refreshed), refreshed);
                result = doGet(url, credentials);
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
