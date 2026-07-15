package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small client for MStock's Type A Top Gainers/Losers endpoint. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MStockGainerLoserService {

    private static final String URL = "https://api.mstock.trade/openapi/typea/losergainer";
    public static final int NSE_EQUITY_EXCHANGE = 1;
    public static final int NSE_EQUITY_SECURITY_ID_CODE = 13;
    public static final int NSE_EQUITY_SEGMENT = 1;

    private final TokenStoreService tokenStoreService;
    private final CryptoService cryptoService;
    private final MStockProperties properties;

    public List<Mover> topGainers() {
        return topGainers(NSE_EQUITY_EXCHANGE, NSE_EQUITY_SECURITY_ID_CODE, NSE_EQUITY_SEGMENT);
    }

    public List<Mover> topLosers() {
        return topLosers(NSE_EQUITY_EXCHANGE, NSE_EQUITY_SECURITY_ID_CODE, NSE_EQUITY_SEGMENT);
    }

    public List<Mover> topGainers(int exchange, int securityIdCode, int segment) {
        return fetch("G", exchange, securityIdCode, segment);
    }

    public List<Mover> topLosers(int exchange, int securityIdCode, int segment) {
        return fetch("L", exchange, securityIdCode, segment);
    }

    private List<Mover> fetch(String typeFlag, int exchange, int securityIdCode, int segment) {
        if (exchange <= 0 || securityIdCode <= 0 || segment <= 0) {
            throw new IllegalArgumentException("exchange, securityIdCode, and segment must be positive integers");
        }
        TokenStoreService.TokenInfo token = requireToken(tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK));
        HttpResult result = callMStock(typeFlag, exchange, securityIdCode, segment, token);
        if (requiresTokenRefresh(result)) {
            log.info("MStock gainers/losers session was rejected (HTTP {}). Refreshing token and retrying once.", result.statusCode());
            TokenStoreService.TokenInfo refreshed = tokenStoreService.refreshToken(Broker.MSTOCK, token);
            if (refreshed != null && StringUtils.hasText(refreshed.getToken())) {
                result = callMStock(typeFlag, exchange, securityIdCode, segment, refreshed);
            } else {
                log.warn("MStock token refresh returned no usable token for gainers/losers request.");
            }
        }
        if (result.statusCode() != 200) {
            throw new IllegalStateException("MStock gainers/losers API returned HTTP " + result.statusCode() + ": " + result.body());
        }
        try {
            JSONObject root = new JSONObject(result.body());
            if (!root.optBoolean("status", false)) throw new IllegalStateException("MStock gainers/losers API failed: " + root.optString("message", result.body()));
            JSONArray data = root.optJSONArray("data");
            List<Mover> movers = new ArrayList<>();
            if (data == null) return movers;
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                String symbol = row.optString("symbol", "").trim().toUpperCase(Locale.ROOT);
                double ltp = row.optDouble("ltp", Double.NaN);
                double changePercent = row.optDouble("per_change", Double.NaN);
                if (StringUtils.hasText(symbol) && Double.isFinite(ltp) && ltp > 0d && Double.isFinite(changePercent)) {
                    movers.add(new Mover(symbol, ltp, changePercent));
                }
            }
            return movers;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse MStock " + ("G".equals(typeFlag) ? "gainers" : "losers") + " response: " + e.getMessage(), e);
        }
    }

    private HttpResult callMStock(String typeFlag,
                                  int exchange,
                                  int securityIdCode,
                                  int segment,
                                  TokenStoreService.TokenInfo token) {
        String apiKey = resolveApiKey(token);
        if (!StringUtils.hasText(apiKey)) throw new IllegalStateException("No MStock API key available for top gainers/losers API.");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("X-Mirae-Version", "1");
            connection.setRequestProperty("Authorization", "token " + apiKey + ":" + token.getToken());
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream output = connection.getOutputStream()) {
                String form = "Exchange=" + exchange + "&SecurityIdCode=" + securityIdCode
                        + "&segment=" + segment + "&TypeFlag=" + typeFlag;
                output.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            java.io.InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (responseStream == null) {
                return new HttpResult(status, "");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                String body = reader.lines().reduce("", (left, right) -> left + right);
                return new HttpResult(status, body);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load MStock " + ("G".equals(typeFlag) ? "gainers" : "losers") + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private TokenStoreService.TokenInfo requireToken(TokenStoreService.TokenInfo token) {
        if (token == null || !StringUtils.hasText(token.getToken())) {
            throw new IllegalStateException("No MStock access token available for top gainers/losers API.");
        }
        return token;
    }

    private boolean requiresTokenRefresh(HttpResult result) {
        if (result == null) return false;
        if (result.statusCode() == 401) return true;
        if (result.statusCode() != 408 || !StringUtils.hasText(result.body())) return false;
        String body = result.body().toLowerCase(Locale.ROOT);
        return body.contains("invalid session") || body.contains("tokenexception");
    }

    private String resolveApiKey(TokenStoreService.TokenInfo token) {
        if (token != null && StringUtils.hasText(token.getApiKey())) {
            try {
                return cryptoService.decrypt(token.getApiKey());
            } catch (Exception ignored) {
                return token.getApiKey();
            }
        }
        return properties.getApiKey();
    }

    public record Mover(String symbol, double ltp, double changePercent) { }
    private record HttpResult(int statusCode, String body) { }
}
