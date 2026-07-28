package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.ShoonyaTokenFetcher;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.service.ShoonyaOAuthService;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShoonyaAuthProvider implements BrokerAuthProvider {
    private final ShoonyaProperties properties;
    private final ShoonyaTokenFetcher tokenFetcher;
    private final ShoonyaOAuthService oauthService;
    private final CryptoService cryptoService;
    @Override public Broker getBroker() { return Broker.SHOONYA; }
    @Override public AuthTokenResult loginAndFetchToken() {
        return oauthService.exchangeAuthorizationCode(tokenFetcher.fetchAuthorizationCode(properties, oauthService.authorizationUrl()));
    }

    /** Uses the same encrypted broker_credentials fields as Sharekhan, falling back to SHOONYA_* properties. */
    @Override public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity credentials) {
        ShoonyaProperties resolved = new ShoonyaProperties();
        resolved.setApiUrl(properties.getApiUrl());
        resolved.setOauthUrl(properties.getOauthUrl());
        resolved.setAccessTokenTtlSeconds(properties.getAccessTokenTtlSeconds());
        resolved.setClientId(first(decrypt(credentials == null ? null : credentials.getApiKey()), properties.getClientId()));
        resolved.setSecretCode(first(decrypt(credentials == null ? null : credentials.getSecretKey()), properties.getSecretCode()));
        resolved.setUid(first(decrypt(credentials == null ? null : credentials.getBrokerUsername()), decrypt(credentials == null ? null : credentials.getClientCode()), properties.getUid()));
        resolved.setPassword(first(decrypt(credentials == null ? null : credentials.getBrokerPassword()), properties.getPassword()));
        resolved.setTotpSecret(first(decrypt(credentials == null ? null : credentials.getTotpSecret()), properties.getTotpSecret()));
        return oauthService.exchangeAuthorizationCode(resolved, tokenFetcher.fetchAuthorizationCode(resolved, authorizationUrl(resolved)));
    }

    private String authorizationUrl(ShoonyaProperties resolved) {
        String separator = resolved.getOauthUrl().contains("?") ? "&" : "?";
        return resolved.getOauthUrl() + separator + "client_id=" + java.net.URLEncoder.encode(resolved.getClientId(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return cryptoService.decrypt(value); } catch (Exception ignored) { return value; }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
