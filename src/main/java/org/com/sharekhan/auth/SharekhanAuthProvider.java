package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.SharekhanTokenFetcher;
import org.com.sharekhan.config.SharekhanProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharekhanAuthProvider implements BrokerAuthProvider {

    private final SharekhanProperties props;
    private final SharekhanTokenFetcher fetcher;
    private final CryptoService cryptoService;

    @Override
    public Broker getBroker() {
        return Broker.SHAREKHAN;
    }

    @Override
    public AuthTokenResult loginAndFetchToken() {
        try {
            String clientCode = props.getClientCode();
            String password = props.getPassword();
            String totpSecret = props.getTotpSecret();
            String apiKey = props.getApiKey();
            String secretKey = props.getSecretKey();

            if (clientCode == null || clientCode.isBlank()) {
                throw new IllegalStateException("Sharekhan client code (app.sharekhan.client-code) not configured");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Sharekhan password (app.sharekhan.password) not configured");
            }
            if (totpSecret == null || totpSecret.isBlank()) {
                throw new IllegalStateException("Sharekhan totp secret (app.sharekhan.totp-secret) not configured");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Sharekhan api key (app.sharekhan.api-key) not configured");
            }
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("Sharekhan secret key (app.sharekhan.secret-key) not configured");
            }

            String token = fetcher.fetchAccessToken(clientCode, password, totpSecret, apiKey, secretKey);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Sharekhan token fetcher returned no token");
            }

            long expiresIn = 8L * 60 * 60; // conservative default
            return new AuthTokenResult(token, expiresIn);
        } catch (Exception e) {
            log.error("❌ Sharekhan login failed", e);
            throw new RuntimeException("Sharekhan login failed: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
        if (creds == null) throw new IllegalArgumentException("Broker credentials required");
        String clientCode = decryptRequired(creds.getClientCode());
        String password = decryptRequired(creds.getBrokerPassword());
        String totpSecret = decryptRequired(creds.getTotpSecret());
        String apiKey = decryptRequired(creds.getApiKey());
        String secretKey = decryptRequired(creds.getSecretKey());

        try {
            if (clientCode == null || clientCode.isBlank()) {
                throw new IllegalStateException("Sharekhan client code (app.sharekhan.client-code) not configured");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Sharekhan password (app.sharekhan.password) not configured");
            }
            if (totpSecret == null || totpSecret.isBlank()) {
                throw new IllegalStateException("Sharekhan totp secret (app.sharekhan.totp-secret) not configured");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Sharekhan api key (app.sharekhan.api-key) not configured");
            }
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("Sharekhan secret key (app.sharekhan.secret-key) not configured");
            }

            String token = fetcher.fetchAccessToken(clientCode, password, totpSecret, apiKey, secretKey);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Sharekhan token fetcher returned no token");
            }

            long expiresIn = 8L * 60 * 60; // conservative default
            return new AuthTokenResult(token, expiresIn);
        } catch (Exception e) {
            log.warn("Sharekhan authentication failed for broker credential {} ({})", creds.getId(), e.getClass().getSimpleName());
            // Browser automation exceptions can contain filled values. Do not retain their text or cause.
            throw new IllegalStateException("Sharekhan authentication failed");
        }
    }
    private String decryptRequired(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Complete all required broker credentials");
        return cryptoService.decrypt(value);
    }
}
