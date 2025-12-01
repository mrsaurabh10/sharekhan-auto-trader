package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.jboss.aerogear.security.otp.Totp;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockAuthProvider implements BrokerAuthProvider {

    private final MStockProperties props;
    private final CryptoService cryptoService;

    private static final String VERIFY_TOTP_URL = "https://api.mstock.trade/openapi/typea/session/verifytotp";

    @Override
    public Broker getBroker() {
        return Broker.MSTOCK;
    }

    @Override
    public AuthTokenResult loginAndFetchToken() {
        try {
            String apiKey = props.getApiKey();
            String totpSecret = props.getTotpSecret();

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("MStock API key (app.mstock.api-key) is not configured");
            }

            String totpCode;
            if (totpSecret != null && !totpSecret.isBlank()) {
                Totp totp = new Totp(totpSecret);
                totpCode = totp.now();
                log.debug("Generated TOTP for MStock via secret");
            } else {
                throw new IllegalStateException("MStock TOTP secret (app.mstock.totp-secret) is not configured");
            }

            // Mask apiKey for safe logging (show first 4 and last 4 chars)
            String maskedApiKey = maskMiddle(apiKey, 4, 4);
            log.info("MStock verify request prepared. apiKey={}, totpPresent={}", maskedApiKey, totpSecret != null && !totpSecret.isBlank());

            String body = "api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&totp=" + URLEncoder.encode(totpCode, StandardCharsets.UTF_8);

            URL url = new URL(VERIFY_TOTP_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + StandardCharsets.UTF_8);
            // Required header per MStock API: specify API version
            conn.setRequestProperty("X-Mirae-Version", "1");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int rc = conn.getResponseCode();
            BufferedReader reader;
            if (rc >= 200 && rc < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }

            String resp = sb.toString().trim();
            log.debug("MStock verifytotp response: {}", resp);

            JSONObject json = null;
            try {
                json = new JSONObject(resp);
            } catch (Exception e) {
                log.warn("MStock verifytotp returned non-JSON response: {}", resp);
            }

            String status = (json != null) ? json.optString("status", "") : "";
            if (!"success".equalsIgnoreCase(status)) {
                // include the full response body to help debugging
                log.warn("MStock verifytotp failed (http {}): {}", rc, resp);
                String msg = (json != null && json.has("message")) ? json.optString("message") : resp;
                throw new RuntimeException("MStock auth failed (http:" + rc + "): " + msg);
            }

            JSONObject data = json.getJSONObject("data");
            String accessToken = data.optString("access_token", null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new RuntimeException("MStock response did not contain access_token; full response: " + resp);
            }

            // expiresIn not provided in the response — use a conservative default (e.g., 8 hours)
            long expiresIn = 8L * 60 * 60;

            return new AuthTokenResult(accessToken, expiresIn);

        } catch (Exception e) {
            log.error("❌ MStock login failed", e);
            throw new RuntimeException("MStock login failed: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
        // If creds provided, prefer values from creds (decrypting them), otherwise fallback to properties
        String apiKey = null;
        String totpSecret = null;
        if (creds != null) {
            try {
                apiKey = creds.getApiKey() != null && !creds.getApiKey().isBlank() ? cryptoService.decrypt(creds.getApiKey()) : null;
            } catch (Exception e) {
                apiKey = creds.getApiKey();
            }
            try {
                totpSecret = creds.getTotpSecret() != null && !creds.getTotpSecret().isBlank() ? cryptoService.decrypt(creds.getTotpSecret()) : null;
            } catch (Exception e) {
                totpSecret = creds.getTotpSecret();
            }
        }

        if (apiKey == null || apiKey.isBlank()) apiKey = props.getApiKey();
        if (totpSecret == null || totpSecret.isBlank()) totpSecret = props.getTotpSecret();

        // Delegate to existing logic by temporarily setting props values via local variables used below
        try {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("MStock API key (app.mstock.api-key) is not configured");
            }

            String totpCode;
            if (totpSecret != null && !totpSecret.isBlank()) {
                Totp totp = new Totp(totpSecret);
                totpCode = totp.now();
                log.debug("Generated TOTP for MStock via secret");
            } else {
                throw new IllegalStateException("MStock TOTP secret (app.mstock.totp-secret) is not configured");
            }

            String maskedApiKey = maskMiddle(apiKey, 4, 4);
            log.info("MStock verify request prepared. apiKey={}, totpPresent={}", maskedApiKey, totpSecret != null && !totpSecret.isBlank());

            String body = "api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&totp=" + URLEncoder.encode(totpCode, StandardCharsets.UTF_8);

            URL url = new URL(VERIFY_TOTP_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + StandardCharsets.UTF_8);
            conn.setRequestProperty("X-Mirae-Version", "1");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int rc = conn.getResponseCode();
            BufferedReader reader;
            if (rc >= 200 && rc < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }

            String resp = sb.toString().trim();
            log.debug("MStock verifytotp response: {}", resp);

            JSONObject json = null;
            try {
                json = new JSONObject(resp);
            } catch (Exception e) {
                log.warn("MStock verifytotp returned non-JSON response: {}", resp);
            }

            String status = (json != null) ? json.optString("status", "") : "";
            if (!"success".equalsIgnoreCase(status)) {
                log.warn("MStock verifytotp failed (http {}): {}", rc, resp);
                String msg = (json != null && json.has("message")) ? json.optString("message") : resp;
                throw new RuntimeException("MStock auth failed (http:" + rc + "): " + msg);
            }

            JSONObject data = json.getJSONObject("data");
            String accessToken = data.optString("access_token", null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new RuntimeException("MStock response did not contain access_token; full response: " + resp);
            }

            long expiresIn = 8L * 60 * 60;

            return new AuthTokenResult(accessToken, expiresIn);

        } catch (Exception e) {
            log.error("❌ MStock login failed", e);
            throw new RuntimeException("MStock login failed: " + e.getMessage(), e);
        }
    }

    private static String maskMiddle(String s, int left, int right) {
        if (s == null) return null;
        int len = s.length();
        if (len <= left + right) return "****";
        StringBuilder sb = new StringBuilder();
        sb.append(s, 0, Math.min(left, len));
        for (int i = 0; i < Math.max(0, len - left - right); i++) sb.append('*');
        sb.append(s, Math.max(left, len - right), len);
        return sb.toString();
    }
}
