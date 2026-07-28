package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.config.ShoonyaProperties;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Implements Shoonya's OAuth authorization-code exchange (NorenWClientAPI/GenAcsTok). */
@Service
@RequiredArgsConstructor
public class ShoonyaOAuthService {
    private final ShoonyaProperties properties;

    public String authorizationUrl() {
        require(properties.getOauthUrl(), "oauth-url");
        require(properties.getClientId(), "client-id");
        return properties.getOauthUrl() + (properties.getOauthUrl().contains("?") ? "&" : "?")
                + "client_id=" + java.net.URLEncoder.encode(properties.getClientId(), StandardCharsets.UTF_8);
    }

    public AuthTokenResult exchangeAuthorizationCode(String code) {
        return exchangeAuthorizationCode(properties, code);
    }

    public AuthTokenResult exchangeAuthorizationCode(ShoonyaProperties credentials, String code) {
        require(code, "authorization code");
        require(credentials.getClientId(), "client-id");
        require(credentials.getSecretCode(), "secret-code");
        require(credentials.getUid(), "uid");
        try {
            String checksum = sha256(credentials.getClientId() + credentials.getSecretCode() + code);
            JSONObject response = post(credentials, new JSONObject().put("code", code).put("checksum", checksum).put("uid", credentials.getUid()));
            String token = response.optString("access_token");
            if (!StringUtils.hasText(token)) throw new IllegalStateException(response.optString("emsg", response.toString()));
            return new AuthTokenResult(token, credentials.getAccessTokenTtlSeconds());
        } catch (Exception e) {
            throw new IllegalStateException("Shoonya OAuth exchange failed: " + e.getMessage(), e);
        }
    }

    private JSONObject post(ShoonyaProperties credentials, JSONObject request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(credentials.getApiUrl() + "/GenAcsTok").toURL().openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) { output.write(("jData=" + request).getBytes(StandardCharsets.UTF_8)); }
        int status = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
            String response = reader.lines().collect(java.util.stream.Collectors.joining());
            if (response.isBlank()) throw new IllegalStateException("Shoonya GenAcsTok returned HTTP " + status + " with no body");
            return new JSONObject(response);
        }
    }
    private static void require(String value, String name) { if (!StringUtils.hasText(value)) throw new IllegalStateException("Shoonya " + name + " is not configured"); }
    private static String sha256(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
