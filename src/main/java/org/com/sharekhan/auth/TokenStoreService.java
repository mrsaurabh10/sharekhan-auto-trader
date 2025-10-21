package org.com.sharekhan.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.enums.Broker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStoreService {

    // use the new persistence service instead of direct repository access
    private final AccessTokenPersistenceService persistenceService;
    private final BrokerAuthProviderRegistry providerRegistry;

    // token storage per broker
    private final Map<Broker, String> tokenMap = new ConcurrentHashMap<>();
    private final Map<Broker, Instant> expiryMap = new ConcurrentHashMap<>();

    @Value("${app.default-broker:SHAREKHAN}")
    private String defaultBrokerName;

    private Broker defaultBroker = Broker.SHAREKHAN;

    @PostConstruct
    public void loadFromDb() {
        // parse configured default broker
        try {
            if (defaultBrokerName != null) {
                try {
                    defaultBroker = Broker.valueOf(defaultBrokerName.trim().toUpperCase());
                } catch (Exception ex) {
                    // try displayName
                    defaultBroker = Broker.fromDisplayName(defaultBrokerName.trim());
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Invalid default broker '{}'. Falling back to SHAREKHAN.", defaultBrokerName);
            defaultBroker = Broker.SHAREKHAN;
        }

        log.info("🔧 Using default broker: {}", defaultBroker);

        // load tokens for all known brokers; if missing/expired, try to fetch via provider
        for (Broker broker : Broker.values()) {
            try {
                AccessTokenEntity latest = persistenceService.findLatestByBroker(broker.getDisplayName());

                boolean needFetch = false;
                if (latest != null && Instant.now().isBefore(latest.getExpiry())) {
                    tokenMap.put(broker, latest.getToken());
                    expiryMap.put(broker, latest.getExpiry());
                    log.info("🔐 Loaded access token from DB for {}. Expiry: {}", broker, latest.getExpiry());
                } else {
                    needFetch = true;
                }

                if (needFetch) {
                    // attempt to fetch token via provider if available
                    BrokerAuthProvider provider = providerRegistry.getProvider(broker);
                    if (provider != null) {
                        try {
                            log.info("🔁 No valid token for {} in DB. Attempting to fetch via provider {}...", broker, provider.getClass().getSimpleName());
                            AuthTokenResult result = provider.loginAndFetchToken();
                            if (result != null && result.token() != null) {
                                // persist and cache
                                Instant expiry = Instant.now().plusSeconds(result.expiresIn() - 60);
                                tokenMap.put(broker, result.token());
                                expiryMap.put(broker, expiry);

                                persistenceService.replaceToken(broker.getDisplayName(), result.token(), expiry);
                                log.info("✅ Fetched and stored token for {} on startup.", broker);
                            } else {
                                log.warn("⚠️ Provider {} returned no token for {}", provider.getClass().getSimpleName(), broker);
                            }
                        } catch (Exception ex) {
                            log.error("❌ Failed to fetch token for {} using provider {}: {}", broker, provider.getClass().getSimpleName(), ex.getMessage());
                        }
                    } else {
                        log.debug("No auth provider registered for {} — skipping startup fetch.", broker);
                    }
                }
            } catch (Exception e) {
                log.error("❌ Error while initializing token for {}: {}", broker, e.getMessage(), e);
            }
        }
    }

    public void loadFromDb(Broker broker) {
        AccessTokenEntity latest = persistenceService.findLatestByBroker(broker.getDisplayName());
        if (latest != null && Instant.now().isBefore(latest.getExpiry())) {
            tokenMap.put(broker, latest.getToken());
            expiryMap.put(broker, latest.getExpiry());
            log.info("🔐 Loaded access token from DB for {}. Expiry: {}", broker, latest.getExpiry());
        }
    }

    // Backwards-compatible default methods (use configured default broker)
    public void updateToken(String token, long expiresInSeconds) {
        updateToken(defaultBroker, token, expiresInSeconds);
    }

    public void updateToken(Broker broker, String token, long expiresInSeconds) {
        Instant expiry = Instant.now().plusSeconds(expiresInSeconds - 60);
        tokenMap.put(broker, token);
        expiryMap.put(broker, expiry);
        log.info("🔐 Access token updated for {}. New expiry: {}", broker, expiry);

        // replace tokens for this broker in DB via transactional persistence service
        persistenceService.replaceToken(broker.getDisplayName(), token, expiry);
    }

    public boolean isExpired() {
        return isExpired(defaultBroker);
    }

    public boolean isExpired(Broker broker) {
        String token = tokenMap.get(broker);
        Instant exp = expiryMap.get(broker);
        return token == null || exp == null || Instant.now().isAfter(exp);
    }

    public String getValidTokenOrNull() {
        return getValidTokenOrNull(defaultBroker);
    }

    public String getValidTokenOrNull(Broker broker) {
        return isExpired(broker) ? null : tokenMap.get(broker);
    }

    public String getAccessToken() {
        return getValidTokenOrNull();
    }

    public String getAccessToken(Broker broker) {
        return getValidTokenOrNull(broker);
    }

    public void clear() {
        clear(defaultBroker);
    }

    public void clear(Broker broker) {
        tokenMap.remove(broker);
        expiryMap.remove(broker);
        persistenceService.deleteByBroker(broker.getDisplayName());
        log.info("🔐 Access token cleared for {}.", broker);
    }
}
