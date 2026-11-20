package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
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

    private String verifyTotpUrl = "https://api.mstock.trade/openapi/typea/session/verifytotp";

    @Override
    public Broker getBroker() {
        return Broker.MSTOCK;
    }

    @Override
    public AuthTokenResult loginAndFetchToken() {
        String apiKey = props.getApiKey();
        String totpSecret = props.getTotpSecret();
        return performLogin(apiKey, totpSecret);
    }

    @Override
    public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
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

        return performLogin(apiKey, totpSecret);
    }

    private AuthTokenResult performLogin(String apiKey, String totpSecret) {
        try {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("MStock API key is not configured");
            }

            String totpCode;
            if (totpSecret != null && !totpSecret.isBlank()) {
                Totp totp = new Totp(totpSecret);
                totpCode = totp.now();
                log.debug("Generated TOTP for MStock via secret");
            } else {
                throw new IllegalStateException("MStock TOTP secret is not configured");
            }

            log.debug("mStock TOTP session request prepared");

            String body = "api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&totp=" + URLEncoder.encode(totpCode, StandardCharsets.UTF_8);

            URL url = new URL(verifyTotpUrl);
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
            try (reader) {
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            } finally { conn.disconnect(); }

            String resp = sb.toString().trim();
            log.debug("MStock verifytotp returned HTTP {}", rc);

            JSONObject json = null;
            try {
                json = new JSONObject(resp);
            } catch (Exception e) {
                log.warn("MStock verifytotp returned non-JSON response (HTTP {})", rc);
            }

            String status = (json != null) ? json.optString("status", "") : "";
            if (rc != 200 || !"success".equalsIgnoreCase(status)) {
                // Never log broker response bodies: successful or failed responses can contain secrets.
                log.warn("MStock verifytotp failed (http {})", rc);
                String msg = "Verify the API key and TOTP setup";
                throw new RuntimeException("MStock auth failed (http:" + rc + "): " + msg);
            }

            JSONObject data = json.getJSONObject("data");
            String accessToken = data.optString("access_token", null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new RuntimeException("MStock response did not contain access_token");
            }

            // mStock sessions expire at the end of the Indian trading date.
            var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            long expiresIn = java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).getSeconds();

            return new AuthTokenResult(accessToken, expiresIn);

        } catch (Exception e) {
            log.warn("mStock login failed ({})", e.getClass().getSimpleName());
            throw new IllegalStateException("mStock login failed: verify the API key and TOTP setup");
        }
    }

}
