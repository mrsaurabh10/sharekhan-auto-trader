package org.com.sharekhan.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.enums.Broker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStoreService {

    // use the new persistence service instead of direct repository access
    private final AccessTokenPersistenceService persistenceService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final org.com.sharekhan.service.BrokerCredentialsService brokerCredentialsService;

    // token storage per broker (global default)
    private final Map<Broker, String> tokenMap = new ConcurrentHashMap<>();
    private final Map<Broker, Instant> expiryMap = new ConcurrentHashMap<>();

    // token storage per broker+customer
    private final Map<String, String> tokenByCustomer = new ConcurrentHashMap<>(); // key = broker.displayName + ':' + customerId
    private final Map<String, Instant> expiryByCustomer = new ConcurrentHashMap<>();

    @Value("${app.default-broker:SHAREKHAN}")
    private String defaultBrokerName;

    private Broker defaultBroker = Broker.SHAREKHAN;

    // Executor for async per-customer token loading (daemon threads so it won't block shutdown)
    private final ExecutorService customerTokenLoader = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger idx = new AtomicInteger(1);
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "token-loader-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

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

        // load global tokens for all brokers
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

                // --- New: asynchronously preload per-customer tokens for any configured broker credentials ---
                try {
                    var credsList = brokerCredentialsService.findAllForBroker(broker.getDisplayName());
                    if (credsList != null && !credsList.isEmpty()) {
                        BrokerAuthProvider provider = providerRegistry.getProvider(broker);
                        for (var creds : credsList) {
                            // schedule each credential as a separate background task so startup isn't blocked
                            customerTokenLoader.submit(() -> {
                                try {
                                    Long custId = creds.getCustomerId();
                                    if (custId == null) return;
                                    String key = broker.getDisplayName() + ":" + custId;

                                    // 1) try to load persisted token for this customer
                                    try {
                                        var opt = persistenceService.findLatestByBrokerAndCustomer(broker.getDisplayName(), custId);
                                        if (opt.isPresent()) {
                                            var ent = opt.get();
                                            if (ent.getExpiry() != null && Instant.now().isBefore(ent.getExpiry())) {
                                                tokenByCustomer.put(key, ent.getToken());
                                                expiryByCustomer.put(key, ent.getExpiry());
                                                log.info("🔐 (async) Loaded access token for {} customer {} from DB. Expiry: {}", broker, custId, ent.getExpiry());
                                                return;
                                            }
                                        }
                                    } catch (Exception ignore) {}

                                    // 2) if no valid persisted token, attempt provider login using stored credentials
                                    if (provider != null) {
                                        try {
                                            var res = provider.loginAndFetchToken(creds);
                                            if (res != null && res.token() != null) {
                                                updateTokenForCustomer(broker, custId, res.token(), res.expiresIn());
                                                log.info("✅ (async) Fetched and stored token for {} customer {}.", broker, custId);
                                            } else {
                                                log.debug("(async) Provider returned no token for {} customer {}.", broker, custId);
                                            }
                                        } catch (Exception ex) {
                                            log.error("❌ (async) Failed to fetch token for {} customer {} via provider: {}", broker, custId, ex.getMessage());
                                        }
                                    } else {
                                        log.debug("No auth provider available for {} — cannot fetch token for customer {}.", broker, custId);
                                    }
                                } catch (Exception inner) {
                                    log.error("❌ (async) Error initializing token for broker {} creds: {}", broker, inner.getMessage(), inner);
                                }
                            });
                            log.debug("Scheduled async token preload for broker {} customer {}", broker, creds.getCustomerId());
                        }
                    }
                } catch (Exception e) {
                    log.debug("No configured credentials found for broker {} or failed to schedule per-customer token preloads: {}", broker, e.getMessage());
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

    // Per-customer load
    public void loadFromDb(Broker broker, Long customerId) {
        if (customerId == null) return;
        Optional<AccessTokenEntity> latestOpt = persistenceService.findLatestByBrokerAndCustomer(broker.getDisplayName(), customerId);
        if (latestOpt.isPresent()) {
            AccessTokenEntity latest = latestOpt.get();
            if (Instant.now().isBefore(latest.getExpiry())) {
                String key = broker.getDisplayName() + ":" + customerId;
                tokenByCustomer.put(key, latest.getToken());
                expiryByCustomer.put(key, latest.getExpiry());
                log.info("🔐 Loaded access token for broker {} customer {} from DB. Expiry: {}", broker, customerId, latest.getExpiry());
            }
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

    // Per-customer update
    public void updateTokenForCustomer(Broker broker, Long customerId, String token, long expiresInSeconds) {
        if (customerId == null) return;
        Instant expiry = Instant.now().plusSeconds(expiresInSeconds - 60);
        String key = broker.getDisplayName() + ":" + customerId;
        tokenByCustomer.put(key, token);
        expiryByCustomer.put(key, expiry);
        log.info("🔐 Access token updated for {} customer {}. New expiry: {}", broker, customerId, expiry);
        persistenceService.replaceTokenForCustomer(broker.getDisplayName(), customerId, token, expiry);
    }

    public boolean isExpired() {
        return isExpired(defaultBroker);
    }

    public boolean isExpired(Broker broker) {
        String token = tokenMap.get(broker);
        Instant exp = expiryMap.get(broker);
        return token == null || exp == null || Instant.now().isAfter(exp);
    }

    public boolean isExpired(Broker broker, Long customerId) {
        if (customerId == null) return isExpired(broker);
        String key = broker.getDisplayName() + ":" + customerId;
        String token = tokenByCustomer.get(key);
        Instant exp = expiryByCustomer.get(key);
        return token == null || exp == null || Instant.now().isAfter(exp);
    }

    public String getValidTokenOrNull() {
        return getValidTokenOrNull(defaultBroker);
    }

    public String getValidTokenOrNull(Broker broker) {
        return isExpired(broker) ? null : tokenMap.get(broker);
    }

    // Per-customer getter (prefer customer-specific token if available and valid)
    public String getValidTokenOrNull(Broker broker, Long customerId) {
        if (customerId != null) {
            String key = broker.getDisplayName() + ":" + customerId;
            // 1) in-memory
            if (!isExpired(broker, customerId) && tokenByCustomer.containsKey(key)) return tokenByCustomer.get(key);

            // 2) try load from DB persistence
            try {
                var opt = persistenceService.findLatestByBrokerAndCustomer(broker.getDisplayName(), customerId);
                if (opt.isPresent()) {
                    var ent = opt.get();
                    if (ent.getExpiry() != null && Instant.now().isBefore(ent.getExpiry())) {
                        tokenByCustomer.put(key, ent.getToken());
                        expiryByCustomer.put(key, ent.getExpiry());
                        return ent.getToken();
                    }
                }
            } catch (Exception ignored) {}

            // 3) try provider auto-login using stored credentials
            try {
                var credsOpt = brokerCredentialsService.findForBrokerAndCustomer(broker.getDisplayName(), customerId);
                var provider = providerRegistry.getProvider(broker);
                if (provider != null && credsOpt.isPresent()) {
                    try {
                        AuthTokenResult res = provider.loginAndFetchToken(credsOpt.get());
                        if (res != null && res.token() != null) {
                            updateTokenForCustomer(broker, customerId, res.token(), res.expiresIn());
                            return res.token();
                        }
                    } catch (Exception e) {
                        // swallow and fallback
                    }
                }
            } catch (Exception ignored) {}
        }

        return getValidTokenOrNull(broker);
    }

    public String getAccessToken() {
        return getValidTokenOrNull();
    }

    public String getAccessToken(Broker broker) {
        return getValidTokenOrNull(broker);
    }

    public String getAccessToken(Broker broker, Long customerId) {
        return getValidTokenOrNull(broker, customerId);
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

    public void clear(Broker broker, Long customerId) {
        if (customerId == null) {
            clear(broker);
            return;
        }
        String key = broker.getDisplayName() + ":" + customerId;
        tokenByCustomer.remove(key);
        expiryByCustomer.remove(key);
        persistenceService.deleteByBroker(broker.getDisplayName());
        log.info("🔐 Access token cleared for {} customer {}.", broker, customerId);
    }

    @PreDestroy
    public void shutdownTokenLoader() {
        try {
            customerTokenLoader.shutdownNow();
        } catch (Exception e) {
            log.debug("Failed to shutdown token loader executor: {}", e.getMessage());
        }
    }
}
