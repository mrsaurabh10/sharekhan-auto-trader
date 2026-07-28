package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.enums.Broker;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Read-only Shoonya client. It intentionally exposes only NorenWClientAPI/GetQuotes. */
@Service
@RequiredArgsConstructor
public class ShoonyaQuoteService {
    private final ShoonyaProperties properties;
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final ShoonyaInstrumentMasterService instrumentMasterService;

    public JSONObject getQuotes(String exchange, String token, String symbol) {
        if (!StringUtils.hasText(exchange)) throw new IllegalArgumentException("exchange is required");
        if (!StringUtils.hasText(token)) token = instrumentMasterService.resolveToken(exchange, symbol);
        String sessionToken = tokenStoreService.getAccessToken(Broker.SHOONYA);
        if (!StringUtils.hasText(sessionToken)) {
            BrokerAuthProvider provider = providerRegistry.getProvider(Broker.SHOONYA);
            if (provider == null) throw new IllegalStateException("Shoonya authentication provider is not registered");
            AuthTokenResult authenticated = provider.loginAndFetchToken();
            tokenStoreService.updateToken(Broker.SHOONYA, authenticated.token(), authenticated.expiresIn());
            sessionToken = authenticated.token();
        }
        JSONObject request = new JSONObject().put("uid", properties.getUid()).put("exch", exchange).put("token", token);
        return post(request, sessionToken);
    }

    private JSONObject post(JSONObject request, String sessionToken) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(properties.getApiUrl() + "/GetQuotes").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
            String body = "jData=" + request;
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
                String response = reader.lines().collect(Collectors.joining());
                if (response.isBlank()) throw new IllegalStateException("Shoonya GetQuotes returned HTTP " + status + " with no body");
                return new JSONObject(response);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Shoonya GetQuotes failed: " + e.getMessage(), e);
        }
    }
}
