package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockIntradayCandleService {

    private static final String INTRADAY_URL_TEMPLATE =
            "https://api.mstock.trade/openapi/typea/instruments/intraday/%s/%s/%s";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final CryptoService cryptoService;

    @Value("${app.mstock.api-key:}")
    private String apiKey;

    public List<IntradayCandle> getIntradayCandles(String exchange, Long instrumentToken, String interval) {
        if (!StringUtils.hasText(exchange) || instrumentToken == null || !StringUtils.hasText(interval)) {
            return List.of();
        }

        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        String normalizedInterval = interval.trim();
        String url = String.format(INTRADAY_URL_TEMPLATE, normalizedExchange, instrumentToken, normalizedInterval);

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
            BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
            if (provider != null) {
                AuthTokenResult refreshed = provider.loginAndFetchToken();
                if (refreshed != null && StringUtils.hasText(refreshed.token())) {
                    tokenStoreService.updateToken(Broker.MSTOCK, refreshed.token(), refreshed.expiresIn());
                    result = doRequest(url, refreshed.token(), effectiveApiKey);
                }
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
            return List.of();
        }

        List<IntradayCandle> parsed = new ArrayList<>();
        for (int i = 0; i < candles.length(); i++) {
            IntradayCandle candle = parseCandle(candles.optJSONArray(i));
            if (candle != null && candle.hasOhlc()) {
                parsed.add(candle);
            }
        }
        parsed.sort(Comparator.comparing(IntradayCandle::date).thenComparing(IntradayCandle::time));
        return parsed;
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
