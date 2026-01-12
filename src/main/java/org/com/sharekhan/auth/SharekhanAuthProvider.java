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
        String clientCode = null;
        String password = null;
        String totpSecret = null;
        String apiKey = null;
        String secretKey = null;

        if (creds != null) {
            try { clientCode = creds.getClientCode() != null && !creds.getClientCode().isBlank() ? cryptoService.decrypt(creds.getClientCode()) : null; } catch (Exception e) { clientCode = creds.getClientCode(); }
            try { password = creds.getBrokerPassword() != null && !creds.getBrokerPassword().isBlank() ? cryptoService.decrypt(creds.getBrokerPassword()) : null; } catch (Exception e) { password = creds.getBrokerPassword(); }
            try { totpSecret = creds.getTotpSecret() != null && !creds.getTotpSecret().isBlank() ? cryptoService.decrypt(creds.getTotpSecret()) : null; } catch (Exception e) { totpSecret = creds.getTotpSecret(); }
            try { apiKey = creds.getApiKey() != null && !creds.getApiKey().isBlank() ? cryptoService.decrypt(creds.getApiKey()) : null; } catch (Exception e) { apiKey = creds.getApiKey(); }
            try { secretKey = creds.getSecretKey() != null && !creds.getSecretKey().isBlank() ? cryptoService.decrypt(creds.getSecretKey()) : null; } catch (Exception e) { secretKey = creds.getSecretKey(); }
        }

        if (clientCode == null || clientCode.isBlank()) clientCode = props.getClientCode();
        if (password == null || password.isBlank()) password = props.getPassword();
        if (totpSecret == null || totpSecret.isBlank()) totpSecret = props.getTotpSecret();
        if (apiKey == null || apiKey.isBlank()) apiKey = props.getApiKey();
        if (secretKey == null || secretKey.isBlank()) secretKey = props.getSecretKey();

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
            log.error("❌ Sharekhan login failed", e);
            throw new RuntimeException("Sharekhan login failed: " + e.getMessage(), e);
        }
    }
}
