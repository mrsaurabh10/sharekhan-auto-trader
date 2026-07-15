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
        TokenStoreService.TokenInfo token = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        if (token == null || !StringUtils.hasText(token.getToken())) {
            throw new IllegalStateException("No MStock access token available for top gainers/losers API.");
        }
        String apiKey = resolveApiKey(token);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("No MStock API key available for top gainers/losers API.");
        }
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
                String body = reader.lines().reduce("", (left, right) -> left + right);
                if (status != 200) throw new IllegalStateException("MStock gainers/losers API returned HTTP " + status + ": " + body);
                JSONObject root = new JSONObject(body);
                if (!root.optBoolean("status", false)) throw new IllegalStateException("MStock gainers/losers API failed: " + root.optString("message", body));
                JSONArray data = root.optJSONArray("data");
                List<Mover> result = new ArrayList<>();
                if (data == null) return result;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject row = data.optJSONObject(i);
                    if (row == null) continue;
                    String symbol = row.optString("symbol", "").trim().toUpperCase(Locale.ROOT);
                    double ltp = row.optDouble("ltp", Double.NaN);
                    double changePercent = row.optDouble("per_change", Double.NaN);
                    if (StringUtils.hasText(symbol) && Double.isFinite(ltp) && ltp > 0d && Double.isFinite(changePercent)) {
                        result.add(new Mover(symbol, ltp, changePercent));
                    }
                }
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load MStock " + ("G".equals(typeFlag) ? "gainers" : "losers") + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
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
}
