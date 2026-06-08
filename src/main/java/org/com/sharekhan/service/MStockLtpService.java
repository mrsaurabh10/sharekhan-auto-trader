package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
    private final CryptoService cryptoService;

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

    private record RequestCredentials(String accessToken, String apiKey, TokenStoreService.TokenInfo tokenInfo) {
    }

    /**
     * Fetch LTPs for the provided instruments. Instruments must be in the form used by the API, e.g. "NSE:ACC", "BSE:ACC".
     * Returns a map from instrument -> object { instrument_token: long, last_price: double } or null when the API returns null for that instrument.
     */
    public Map<String, Map<String, Object>> fetchLtp(List<String> instruments) {
        if (instruments == null || instruments.isEmpty()) return Collections.emptyMap();

        try {
            RequestCredentials credentials = resolveCredentials(null);
            StringBuilder sb = new StringBuilder(LTP_URL);
            sb.append("?");
            boolean first = true;
            for (String inst : instruments) {
                if (!first) sb.append("&");
                first = false;
                sb.append("i=").append(URLEncoder.encode(inst, StandardCharsets.UTF_8));
            }

            String urlStr = sb.toString();
            log.debug("MStock LTP request ({} instruments): {}", instruments.size(), urlStr);

            // Attempt request with the required Authorization format: 'token {apiKey}:{accessToken}'
            HttpResult res = doRequestWithApiKey(urlStr, credentials.accessToken(), credentials.apiKey());

            // if token-related error, try refresh via provider and retry once
            if (res.code == 401 || indicatesTokenException(res.body)) {
                log.warn("MStock LTP returned token error (http {}), attempting refresh via provider", res.code);
                RequestCredentials refreshedCredentials = refreshCredentials(credentials);
                if (refreshedCredentials != null) {
                    credentials = refreshedCredentials;
                    res = doRequestWithApiKey(urlStr, credentials.accessToken(), credentials.apiKey());
                } else {
                    log.warn("Unable to refresh MStock token for failed LTP request");
                }
            }

            if (res == null) {
                throw new RuntimeException("MStock LTP request failed: empty response");
            }

            if (res.code != 200) {
                throw new MStockLtpException(res.code, res.body);
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

        } catch (MStockLtpException e) {
            throw e;
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

    private HttpResult doRequestWithApiKey(String urlStr, String accessToken, String apiKey) throws Exception {
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

    private HttpResult doRequestWithApiKey(String urlStr, String accessToken) throws Exception {
        return doRequestWithApiKey(urlStr, accessToken, this.apiKey);
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

        try {
            RequestCredentials credentials = resolveCredentials(customerId);
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

            HttpResult res = doRequestWithApiKey(urlStr, credentials.accessToken(), credentials.apiKey());

            if (res.code == 401 || indicatesTokenException(res.body)) {
                log.warn("MStock LTP returned token error (http {}), attempting refresh via provider", res.code);
                RequestCredentials refreshedCredentials = refreshCredentials(credentials);
                if (refreshedCredentials != null) {
                    credentials = refreshedCredentials;
                    res = doRequestWithApiKey(urlStr, credentials.accessToken(), credentials.apiKey());
                } else {
                    log.warn("Unable to refresh MStock token for failed LTP request");
                }
            }

            if (res == null) {
                throw new RuntimeException("MStock LTP request failed: empty response");
            }

            if (res.code != 200) {
                throw new MStockLtpException(res.code, res.body);
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

        } catch (MStockLtpException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ Failed to fetch MStock LTP", e);
            throw new RuntimeException("Failed to fetch MStock LTP: " + e.getMessage(), e);
        }
    }

    private RequestCredentials resolveCredentials(Long customerId) {
        TokenStoreService.TokenInfo tokenInfo = customerId != null
                ? tokenStoreService.getTokenInfo(Broker.MSTOCK, customerId)
                : tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        String accessToken = tokenInfo != null ? tokenInfo.getToken() : null;
        String effectiveApiKey = resolveApiKey(tokenInfo);

        if (!StringUtils.hasText(accessToken)) {
            accessToken = customerId != null
                    ? tokenStoreService.getAccessToken(Broker.MSTOCK, customerId)
                    : tokenStoreService.getAccessToken(Broker.MSTOCK);
        }

        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }

        if (!StringUtils.hasText(effectiveApiKey)) {
            log.warn("MStock API key is not configured or resolved");
        }

        return new RequestCredentials(accessToken, effectiveApiKey, tokenInfo);
    }

    private RequestCredentials refreshCredentials(RequestCredentials current) {
        TokenStoreService.TokenInfo refreshedTokenInfo = tokenStoreService.refreshToken(
                Broker.MSTOCK, current != null ? current.tokenInfo() : null);
        if (refreshedTokenInfo == null || !StringUtils.hasText(refreshedTokenInfo.getToken())) {
            return null;
        }
        return new RequestCredentials(refreshedTokenInfo.getToken(), resolveApiKey(refreshedTokenInfo), refreshedTokenInfo);
    }

    private String resolveApiKey(TokenStoreService.TokenInfo tokenInfo) {
        String effectiveApiKey = this.apiKey;
        if (tokenInfo != null && StringUtils.hasText(tokenInfo.getApiKey())) {
            try {
                effectiveApiKey = cryptoService.decrypt(tokenInfo.getApiKey());
            } catch (Exception e) {
                effectiveApiKey = tokenInfo.getApiKey();
            }
        }
        return effectiveApiKey;
    }
}
