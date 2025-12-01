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

import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStoreService {

    // use the new persistence service instead of direct repository access
    private final AccessTokenPersistenceService persistenceService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final org.com.sharekhan.service.BrokerCredentialsService brokerCredentialsService;
    private final AppUserRepository appUserRepository; // new: to enumerate users
    private final BrokerCredentialsRepository brokerCredentialsRepository; // new: to find creds by customer

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

    //@PostConstruct
    public void loadFromDb() {
//        // parse configured default broker
//        try {
//            if (defaultBrokerName != null) {
//                try {
//                    defaultBroker = Broker.valueOf(defaultBrokerName.trim().toUpperCase());
//                } catch (Exception ex) {
//                    // try displayName
//                    defaultBroker = Broker.fromDisplayName(defaultBrokerName.trim());
//                }
//            }
//        } catch (Exception e) {
//            log.warn("⚠️ Invalid default broker '{}'. Falling back to SHAREKHAN.", defaultBrokerName);
//            defaultBroker = Broker.SHAREKHAN;
//        }

        log.info("🔧 Using default broker: {}", defaultBroker);

        // Instead of iterating Broker enum, iterate the broker credentials table and authenticate per credentials row.
        try {
            var allCreds = brokerCredentialsRepository.findAll();
            if (allCreds != null && !allCreds.isEmpty()) {
                for (var creds : allCreds) {
                    customerTokenLoader.submit(() -> {
                        try {
                            if (creds == null) return;
                            String brokerName = creds.getBrokerName();
                            if (brokerName == null) return;

                            // Resolve Broker enum from display name or name
                            Broker brokerEnum = null;
                            try {
                                brokerEnum = Broker.fromDisplayName(brokerName);
                            } catch (Exception ex) {
                                try { brokerEnum = Broker.valueOf(brokerName.trim().toUpperCase()); } catch (Exception ex2) { brokerEnum = null; }
                            }
                            if (brokerEnum == null) {
                                log.debug("Unknown broker '{}' for broker credential id {} - skipping", brokerName, creds.getId());
                                return;
                            }

                            BrokerAuthProvider provider = providerRegistry.getProvider(brokerEnum);
                            if (provider == null) {
                                log.debug("No auth provider registered for {} - skipping credential id {}", brokerEnum, creds.getId());
                                return;
                            }

                            // If a valid token already exists in DB for this credential (by customerId or credential id), prefer it
                            Long customerId = creds.getCustomerId();
                            try {
                                Optional<AccessTokenEntity> existingOpt = Optional.empty();
                                if (customerId != null) {
                                    existingOpt = persistenceService.findLatestByBrokerAndCustomer(brokerEnum.getDisplayName(), customerId);
                                }
                                if (existingOpt.isPresent()) {
                                    AccessTokenEntity ent = existingOpt.get();
                                    if (ent.getExpiry() != null && Instant.now().isBefore(ent.getExpiry())) {
                                        String key = brokerEnum.getDisplayName() + ":" + customerId;
                                        tokenByCustomer.put(key, ent.getToken());
                                        expiryByCustomer.put(key, ent.getExpiry());
                                        log.info("🔐 Loaded cached token for {} customer {} from DB. Expiry: {}", brokerEnum, customerId, ent.getExpiry());
                                        return;
                                    }
                                }
                            } catch (Exception ignore) {}

                            // Try provider login using this credential row
                            try {
                                AuthTokenResult res = provider.loginAndFetchToken(creds);
                                if (res != null && res.token() != null) {
                                    Instant expiry = Instant.now().plusSeconds(res.expiresIn() - 60);
                                    // Persist token associated with this broker credential (and customer if present)
                                    if (creds.getId() != null) {
                                        persistenceService.replaceTokenForBrokerCredentials(brokerEnum.getDisplayName(), creds.getId(), res.token(), expiry);
                                    }
                                    if (creds.getCustomerId() != null) {
                                        String key = brokerEnum.getDisplayName() + ":" + creds.getCustomerId();
                                        tokenByCustomer.put(key, res.token());
                                        expiryByCustomer.put(key, expiry);
                                        // Also persist as customer token mapped to userId if appUserId exists on creds
                                        try {
                                            // some credentials may have an associated appUserId field - persist mapping if available
                                            if (creds.getAppUserId() != null) {
                                                persistenceService.replaceTokenForCustomer(brokerEnum.getDisplayName(), creds.getCustomerId(), creds.getAppUserId(), res.token(), expiry);
                                            }
                                        } catch (Exception ignore) {}
                                    } else {
                                        // No customerId: update global token map as fallback for this broker
                                        tokenMap.put(brokerEnum, res.token());
                                        expiryMap.put(brokerEnum, expiry);
                                        persistenceService.replaceToken(brokerEnum.getDisplayName(), res.token(), expiry);
                                    }

                                    log.info("✅ Fetched and persisted token for broker credential id {} (broker={})", creds.getId(), brokerEnum.getDisplayName());
                                } else {
                                    log.warn("Provider returned no token for broker credential id {} (broker={})", creds.getId(), brokerEnum.getDisplayName());
                                }
                            } catch (Exception ex) {
                                log.error("❌ Failed provider login for broker credential id {} (broker={}): {}", creds.getId(), brokerEnum.getDisplayName(), ex.getMessage());
                            }

                        } catch (Exception inner) {
                            log.error("❌ (async) Error processing broker credential id {}: {}", creds != null ? creds.getId() : null, inner.getMessage(), inner);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("❌ Error loading broker credentials for token initialization: {}", e.getMessage(), e);
        }

        // --- New: ensure we also inspect all AppUser entries and pre-load tokens for any configured broker credentials ---
        try {
            var users = appUserRepository.findAll();
            for (var user : users) {
                try {
                    Long appUserId = user.getId();
                    if (appUserId == null) continue;

                    // find all broker credentials rows for this app user across brokers
                    java.util.List<org.com.sharekhan.entity.BrokerCredentialsEntity> credsForUser = brokerCredentialsRepository.findByAppUserId(appUserId);
                    if (credsForUser == null || credsForUser.isEmpty()) continue;

                    for (org.com.sharekhan.entity.BrokerCredentialsEntity creds : credsForUser) {
                        final org.com.sharekhan.entity.BrokerCredentialsEntity credsFinal = creds; // capture for lambda
                        customerTokenLoader.submit(() -> {
                            try {
                                Long custId = credsFinal.getCustomerId();
                                if (custId == null) return;

                                String brokerName = credsFinal.getBrokerName();
                                org.com.sharekhan.enums.Broker brokerEnum;
                                try {
                                    brokerEnum = org.com.sharekhan.enums.Broker.fromDisplayName(brokerName);
                                } catch (Exception ex) {
                                    // try valueOf
                                    try { brokerEnum = org.com.sharekhan.enums.Broker.valueOf(brokerName.trim().toUpperCase()); } catch (Exception ex2) { brokerEnum = null; }
                                }
                                if (brokerEnum == null) {
                                    log.debug("Unknown broker '{}' for appUser {} - skipping token preload", credsFinal.getBrokerName(), appUserId);
                                    return;
                                }

                                String key = brokerEnum.getDisplayName() + ":" + custId;

                                // skip if already loaded and valid
                                if (tokenByCustomer.containsKey(key) && expiryByCustomer.containsKey(key) && Instant.now().isBefore(expiryByCustomer.get(key))) {
                                    return;
                                }

                                // attempt persisted load
                                try {
                                    var opt = persistenceService.findLatestByBrokerAndCustomer(brokerEnum.getDisplayName(), custId);
                                    if (opt.isPresent()) {
                                        var ent = opt.get();
                                        if (ent.getExpiry() != null && Instant.now().isBefore(ent.getExpiry())) {
                                            tokenByCustomer.put(key, ent.getToken());
                                            expiryByCustomer.put(key, ent.getExpiry());
                                            log.info("🔐 (user-scan) Loaded access token for {} customer {} from DB. Expiry: {}", brokerEnum, custId, ent.getExpiry());
                                            return;
                                        }
                                    }
                                } catch (Exception ignore) {}

                                // else attempt provider auto-login using stored creds
                                var provider = providerRegistry.getProvider(brokerEnum);
                                if (provider != null) {
                                    try {
                                        var res = provider.loginAndFetchToken(credsFinal);
                                        if (res != null && res.token() != null) {
                                            // associate persisted token with AppUser.id when available
                                            // persist token mapped to this broker customer (and optionally userId)
                                            persistenceService.replaceTokenForCustomer(brokerEnum.getDisplayName(), custId, user.getId(), res.token(), Instant.now().plusSeconds(res.expiresIn() - 60));
                                            log.info("✅ (user-scan) Fetched and stored token for {} customer {}.", brokerEnum, custId);
                                            return;
                                        }
                                    } catch (Exception ex) {
                                        log.error("❌ (user-scan) Failed to fetch token for {} customer {} via provider: {}", brokerEnum, custId, ex.getMessage());
                                    }
                                } else {
                                    log.debug("No auth provider available for {} — cannot fetch token for customer {}.", brokerEnum, custId);
                                }

                            } catch (Exception e) {
                                log.error("❌ Error preloading token for user {} creds: {}", user.getId(), e.getMessage(), e);
                            }
                        });
                    }
                } catch (Exception ue) {
                    log.debug("Failed scanning user {} for broker creds: {}", user.getId(), ue.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to scan AppUser table for broker credentials on startup: {}", e.getMessage());
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
        updateTokenForCustomer(broker, customerId, null, token, expiresInSeconds);
    }

    // New overload: keep userId associated with the token when available
    public void updateTokenForCustomer(Broker broker, Long customerId, Long userId, String token, long expiresInSeconds) {
        if (customerId == null) return;
        Instant expiry = Instant.now().plusSeconds(expiresInSeconds - 60);
        String key = broker.getDisplayName() + ":" + customerId;
        tokenByCustomer.put(key, token);
        expiryByCustomer.put(key, expiry);
        log.info("🔐 Access token updated for {} customer {}. New expiry: {} (userId={})", broker, customerId, expiry, userId);
        persistenceService.replaceTokenForCustomer(broker.getDisplayName(), customerId, userId, token, expiry);
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
        // try in-memory first
        String token = tokenByCustomer.get(key);
        Instant exp = expiryByCustomer.get(key);

        // If not found in-memory, attempt to load latest persisted token for this broker+customer
        if ((token == null || exp == null)) {
            try {
                var opt = persistenceService.findLatestByBrokerAndCustomer(broker.getDisplayName(), customerId);
                if (opt.isPresent()) {
                    var ent = opt.get();
                    if (ent.getToken() != null && ent.getExpiry() != null && Instant.now().isBefore(ent.getExpiry())) {
                        token = ent.getToken();
                        exp = ent.getExpiry();
                        tokenByCustomer.put(key, token);
                        expiryByCustomer.put(key, exp);
                        log.debug("Loaded token from DB into cache for {} (customer={}) exp={}", broker.getDisplayName(), customerId, exp);
                    } else {
                        // persisted token missing or expired — ensure maps don't hold stale entries
                        tokenByCustomer.remove(key);
                        expiryByCustomer.remove(key);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to load persisted token for broker {} customer {}: {}", broker.getDisplayName(), customerId, e.getMessage());
            }
        }

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
