package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticates with Shoonya's Noren QuickAuth endpoint and returns its session token. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShoonyaAuthProvider implements BrokerAuthProvider {
    private final ShoonyaProperties properties;
    private final CryptoService cryptoService;

    @Override
    public Broker getBroker() {
        return Broker.SHOONYA;
    }

    @Override
    public AuthTokenResult loginAndFetchToken() {
        return login(properties.getUserId(), properties.getPassword(), properties.getVendorCode(),
                properties.getApiKey(), properties.getDeviceId(), properties.getTotpSecret());
    }

    @Override
    public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity credentials) {
        if (credentials == null) return loginAndFetchToken();
        return login(value(credentials.getBrokerUsername(), properties.getUserId()),
                decrypt(credentials.getBrokerPassword(), properties.getPassword()),
                value(credentials.getClientCode(), properties.getVendorCode()),
                decrypt(credentials.getApiKey(), properties.getApiKey()),
                value(credentials.getSecretKey(), properties.getDeviceId()),
                decrypt(credentials.getTotpSecret(), properties.getTotpSecret()));
    }

    private AuthTokenResult login(String userId, String password, String vendorCode, String apiKey,
                                  String deviceId, String totpSecret) {
        require(userId, "user-id");
        require(password, "password");
        require(vendorCode, "vendor-code");
        require(apiKey, "api-key");
        require(deviceId, "device-id");
        require(totpSecret, "totp-secret");
        try {
            JSONObject request = new JSONObject();
            request.put("apkversion", "1.0.0");
            request.put("uid", userId);
            request.put("pwd", sha256(password));
            request.put("factor2", new Totp(totpSecret).now());
            request.put("vc", vendorCode);
            request.put("appkey", sha256(apiKey));
            request.put("imei", deviceId);
            request.put("source", "API");

            JSONObject response = post("/QuickAuth", request, null);
            if (!"Ok".equalsIgnoreCase(response.optString("stat"))) {
                throw new IllegalStateException("Shoonya authentication failed: " + response.optString("emsg", "unknown error"));
            }
            String token = response.optString("susertoken");
            if (!StringUtils.hasText(token)) throw new IllegalStateException("Shoonya authentication did not return susertoken");
            // Shoonya sessions expire at the end of the trading day. This deliberately conservative value
            // makes the application authenticate again instead of attempting any unsupported refresh API.
            return new AuthTokenResult(token, 8 * 60 * 60);
        } catch (Exception e) {
            // Do not log request fields: they contain the password hash, TOTP, and API-key hash.
            // The response is safe to retain and is essential when Shoonya returns an HTML gateway page.
            log.error("Shoonya authentication request failed: {}", e.getMessage());
            throw new IllegalStateException("Shoonya authentication failed: " + e.getMessage(), e);
        }
    }

    private JSONObject post(String path, JSONObject request, String sessionToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(properties.getAuthUrl() + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        // Noren's endpoint expects the JSON object as the raw jData form value (per its curl example).
        String body = "jData=" + request + (sessionToken == null ? "" : "&jKey=" + sessionToken);
        try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        int status = connection.getResponseCode();
        try (var reader = new java.io.BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
            String response = reader.lines().collect(java.util.stream.Collectors.joining());
            if (response.isBlank()) throw new IllegalStateException("Shoonya QuickAuth returned HTTP " + status + " with an empty response");
            try {
                return new JSONObject(response);
            } catch (Exception e) {
                throw new IllegalStateException("Shoonya QuickAuth returned HTTP " + status
                        + " with non-JSON response: " + abbreviate(response), e);
            }
        }
    }

    private String decrypt(String encrypted, String fallback) {
        if (!StringUtils.hasText(encrypted)) return fallback;
        try { return cryptoService.decrypt(encrypted); } catch (Exception ignored) { return encrypted; }
    }
    private static String value(String preferred, String fallback) { return StringUtils.hasText(preferred) ? preferred : fallback; }
    private static void require(String value, String name) { if (!StringUtils.hasText(value)) throw new IllegalStateException("Shoonya " + name + " is not configured"); }
    private static String sha256(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static String abbreviate(String value) { return value.length() <= 500 ? value : value.substring(0, 500) + "..."; }
}
