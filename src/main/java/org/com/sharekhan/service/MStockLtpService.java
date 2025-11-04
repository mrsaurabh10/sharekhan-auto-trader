package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockLtpService {

    private static final String LTP_URL = "https://api.mstock.trade/openapi/typea/instruments/quote/ltp";
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;

    // Injected API key from application properties: app.mstock.api-key
    @Value("${app.mstock.api-key:}")
    private String apiKey;

    private static class HttpResult {
        int code;
        String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * Fetch LTPs for the provided instruments. Instruments must be in the form used by the API, e.g. "NSE:ACC", "BSE:ACC".
     * Returns a map from instrument -> object { instrument_token: long, last_price: double } or null when the API returns null for that instrument.
     */
    public Map<String, Map<String, Object>> fetchLtp(List<String> instruments) {
        if (instruments == null || instruments.isEmpty()) return Collections.emptyMap();

        String storedToken = tokenStoreService.getAccessToken(Broker.MSTOCK);
        if (storedToken == null) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("MStock API key (app.mstock.api-key) is not configured");
        }

        try {
            StringBuilder sb = new StringBuilder(LTP_URL);
            sb.append("?");
            boolean first = true;
            for (String inst : instruments) {
                if (!first) sb.append("&");
                first = false;
                sb.append("i=").append(URLEncoder.encode(inst, StandardCharsets.UTF_8));
            }

            String urlStr = sb.toString();
            log.debug("MStock LTP URL: {}", urlStr);

            // Attempt request with the required Authorization format: 'token {apiKey}:{accessToken}'
            HttpResult res = doRequestWithApiKey(urlStr, storedToken);

            // if token-related error, try refresh via provider and retry once
            if (res.code == 401 || indicatesTokenException(res.body)) {
                log.warn("MStock LTP returned token error (http {}), attempting refresh via provider", res.code);
                BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
                if (provider != null) {
                    try {
                        AuthTokenResult auth = provider.loginAndFetchToken();
                        if (auth != null && auth.token() != null) {
                            tokenStoreService.updateToken(Broker.MSTOCK, auth.token(), auth.expiresIn());
                            storedToken = auth.token();
                            // retry with refreshed token
                            res = doRequestWithApiKey(urlStr, storedToken);
                        } else {
                            log.warn("Provider returned no token during refresh");
                        }
                    } catch (Exception e) {
                        log.error("Failed to refresh MStock token via provider", e);
                    }
                } else {
                    log.warn("No MStock auth provider registered to refresh token");
                }
            }

            if (res == null) {
                throw new RuntimeException("MStock LTP request failed: empty response");
            }

            if (res.code != 200) {
                log.warn("MStock LTP failed (http {}): {}", res.code, res.body);
                throw new RuntimeException("MStock LTP request failed (http:" + res.code + "): " + res.body);
            }

            log.debug("MStock LTP response (http {}): {}", res.code, res.body);

            JSONObject root = new JSONObject(res.body);
            String status = root.optString("status", "");
            if (!"success".equalsIgnoreCase(status)) {
                throw new RuntimeException("MStock LTP request failed (http:" + res.code + "): " + res.body);
            }

            JSONObject data = root.optJSONObject("data");
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (String inst : instruments) {
                if (data == null || data.isNull(inst)) {
                    result.put(inst, null);
                } else {
                    JSONObject instObj = data.optJSONObject(inst);
                    if (instObj == null) {
                        result.put(inst, null);
                    } else {
                        Map<String, Object> obj = new LinkedHashMap<>();
                        // instrument_token may be integer
                        if (instObj.has("instrument_token") && !instObj.isNull("instrument_token")) {
                            long token = instObj.optLong("instrument_token", -1);
                            obj.put("instrument_token", token == -1 ? null : token);
                        } else {
                            obj.put("instrument_token", null);
                        }

                        if (instObj.has("last_price") && !instObj.isNull("last_price")) {
                            double lastPrice = instObj.optDouble("last_price");
                            obj.put("last_price", lastPrice);
                        } else {
                            obj.put("last_price", null);
                        }

                        result.put(inst, obj);
                    }
                }
            }

            return result;

        } catch (Exception e) {
            log.error("❌ Failed to fetch MStock LTP", e);
            throw new RuntimeException("Failed to fetch MStock LTP: " + e.getMessage(), e);
        }
    }

    /** Convenience single-instrument fetch. Returns the instrument object map or null. */
    public Map<String, Object> fetchLtpForInstrument(String instrument) {
        Map<String, Map<String, Object>> map = fetchLtp(Collections.singletonList(instrument));
        return map.get(instrument);
    }

    private HttpResult doRequestWithApiKey(String urlStr, String accessToken) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("X-Mirae-Version", "1");

        // Authorization must be: token {apiKey}:{accessToken}
        String authValue;
        if (apiKey != null && !apiKey.isBlank()) {
            authValue = "token " + apiKey + ":" + accessToken;
        } else {
            // fallback to token {accessToken} if apiKey not present (avoid breaking existing setups)
            authValue = "token " + accessToken;
        }
        conn.setRequestProperty("Authorization", authValue);

        int rc = conn.getResponseCode();
        BufferedReader reader;
        if (rc >= 200 && rc < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
        }

        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(line).append('\n');
        }
        String resp = out.toString().trim();
        return new HttpResult(rc, resp);
    }

    private boolean indicatesTokenException(String body) {
        try {
            if (body == null || body.isBlank()) return false;
            JSONObject root = new JSONObject(body);
            String errorType = root.optString("error_type", null);
            if (errorType != null && errorType.equalsIgnoreCase("TokenException")) return true;
            // older format: { "Error": { "Code": "ApiVersionUnspecified", ... } }
            if (root.has("Error")) return true;
        } catch (Exception e) {
            // ignore parse errors
        }
        return false;
    }

    /** Fetch LTP using a customer-specific token when available */
    public Map<String, Map<String, Object>> fetchLtp(List<String> instruments, Long customerId) {
        if (instruments == null || instruments.isEmpty()) return Collections.emptyMap();

        String storedToken = tokenStoreService.getAccessToken(Broker.MSTOCK, customerId);
        if (storedToken == null) {
            // fallback to global token
            storedToken = tokenStoreService.getAccessToken(Broker.MSTOCK);
        }
        if (storedToken == null) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }

        // delegate to existing implementation by temporarily using the storedToken path
        // To avoid duplicating logic, we will copy the implementation but using storedToken variable
        try {
            StringBuilder sb = new StringBuilder(LTP_URL);
            sb.append("?");
            boolean first = true;
            for (String inst : instruments) {
                if (!first) sb.append("&");
                first = false;
                sb.append("i=").append(URLEncoder.encode(inst, StandardCharsets.UTF_8));
            }

            String urlStr = sb.toString();
            log.debug("MStock LTP URL: {}", urlStr);

            HttpResult res = doRequestWithApiKey(urlStr, storedToken);

            if (res.code == 401 || indicatesTokenException(res.body)) {
                log.warn("MStock LTP returned token error (http {}), attempting refresh via provider", res.code);
                BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
                if (provider != null) {
                    try {
                        AuthTokenResult auth = provider.loginAndFetchToken();
                        if (auth != null && auth.token() != null) {
                            tokenStoreService.updateToken(Broker.MSTOCK, auth.token(), auth.expiresIn());
                            storedToken = auth.token();
                            res = doRequestWithApiKey(urlStr, storedToken);
                        } else {
                            log.warn("Provider returned no token during refresh");
                        }
                    } catch (Exception e) {
                        log.error("Failed to refresh MStock token via provider", e);
                    }
                } else {
                    log.warn("No MStock auth provider registered to refresh token");
                }
            }

            if (res == null) {
                throw new RuntimeException("MStock LTP request failed: empty response");
            }

            if (res.code != 200) {
                log.warn("MStock LTP failed (http {}): {}", res.code, res.body);
                throw new RuntimeException("MStock LTP request failed (http:" + res.code + "): " + res.body);
            }

            log.debug("MStock LTP response (http {}): {}", res.code, res.body);

            JSONObject root = new JSONObject(res.body);
            String status = root.optString("status", "");
            if (!"success".equalsIgnoreCase(status)) {
                throw new RuntimeException("MStock LTP request failed (http:" + res.code + "): " + res.body);
            }

            JSONObject data = root.optJSONObject("data");
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (String inst : instruments) {
                if (data == null || data.isNull(inst)) {
                    result.put(inst, null);
                } else {
                    JSONObject instObj = data.optJSONObject(inst);
                    if (instObj == null) {
                        result.put(inst, null);
                    } else {
                        Map<String, Object> obj = new LinkedHashMap<>();
                        if (instObj.has("instrument_token") && !instObj.isNull("instrument_token")) {
                            long token = instObj.optLong("instrument_token", -1);
                            obj.put("instrument_token", token == -1 ? null : token);
                        } else {
                            obj.put("instrument_token", null);
                        }

                        if (instObj.has("last_price") && !instObj.isNull("last_price")) {
                            double lastPrice = instObj.optDouble("last_price");
                            obj.put("last_price", lastPrice);
                        } else {
                            obj.put("last_price", null);
                        }

                        result.put(inst, obj);
                    }
                }
            }

            return result;

        } catch (Exception e) {
            log.error("❌ Failed to fetch MStock LTP", e);
            throw new RuntimeException("Failed to fetch MStock LTP: " + e.getMessage(), e);
        }
    }
}
