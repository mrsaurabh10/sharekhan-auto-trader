package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @Scheduled(fixedDelay = 3600_000) // every hour
    public void refreshTokenIfNeeded() {
        // First, refresh tokens for each configured broker credential (per-customer)
        try {
            var allCreds = brokerCredentialsRepository.findAll();
            if (allCreds != null) {
                for (BrokerCredentialsEntity creds : allCreds) {
                    try {
                        refreshCredential(creds, false);
                    } catch (Exception e) {
                        log.error("❌ Error refreshing credential id {}: {}", creds != null ? creds.getId() : null, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error iterating broker credentials for refresh: {}", e.getMessage(), e);
        }

        // Fallback: refresh global tokens for brokers that need it
//        for (BrokerAuthProvider provider : providerRegistry.getAllProviders()) {
//            Broker broker = provider.getBroker();
//            if (tokenStoreService.isExpired(broker)) {
//                log.info("🔁 Access token expired for {}. Re-authenticating...", broker);
//                try {
//                    AuthTokenResult result = provider.loginAndFetchToken();
//                    tokenStoreService.updateToken(broker, result.token(), result.expiresIn());
//                    log.info("✅ Access token refreshed for {}.", broker);
//                } catch (Exception e) {
//                    log.error("❌ Failed to refresh token for {}: {}", broker, e.getMessage(), e);
//                }
//            }
//        }
    }


    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshTokenAt830IST() {
        log.info("⏰ Scheduled 8:30 AM IST token refresh starting...");

        // Refresh per-credential tokens first (force refresh)
        try {
            var allCreds = brokerCredentialsRepository.findAll();
            if (allCreds != null) {
                for (BrokerCredentialsEntity creds : allCreds) {
                    try {
                        refreshCredential(creds, true);
                    } catch (Exception e) {
                        log.error("❌ Error during scheduled refresh for credential id {}: {}", creds != null ? creds.getId() : null, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error during scheduled broker credentials refresh: {}", e.getMessage(), e);
        }

//        // Then refresh global broker tokens
//        for (BrokerAuthProvider provider : providerRegistry.getAllProviders()) {
//            Broker broker = provider.getBroker();
//            try {
//                AuthTokenResult result = provider.loginAndFetchToken();
//                tokenStoreService.updateToken(broker, result.token(), result.expiresIn());
//                log.info("✅ Token refreshed successfully for {} at 8:30 AM IST.", broker);
//            } catch (Exception e) {
//                log.error("❌ Failed scheduled token refresh at 8:30 AM IST for {}: {}", broker, e.getMessage(), e);
//            }
//        }
    }

    // --- helper to refresh a single broker credential; 'force' indicates this is a scheduled forced refresh ---
    private void refreshCredential(BrokerCredentialsEntity creds, boolean force) {
        if (creds == null) return;
        String brokerName = creds.getBrokerName();
        if (brokerName == null) return;

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

        // If not forced, only refresh when token is expired for this broker+customer
        try {
            if (!force) {
                boolean expired = tokenStoreService.isExpired(brokerEnum, creds.getCustomerId());
                if (!expired) {
                    log.debug("Token for broker {} customer {} is still valid - skipping refresh for credential id {}", brokerEnum.getDisplayName(), creds.getCustomerId(), creds.getId());
                    return;
                }
            }
        } catch (Exception e) {
            // on error determining expiry, proceed to attempt refresh (safer)
            log.debug("Could not determine token expiry for broker credential id {}: {} - proceeding to refresh", creds.getId(), e.getMessage());
        }

        try {
            AuthTokenResult res = provider.loginAndFetchToken(creds);
            if (res != null && res.token() != null) {
                tokenStoreService.updateTokenForCustomer(brokerEnum, creds.getCustomerId(), creds.getAppUserId(), res.token(), res.expiresIn());
                log.info("{} token refreshed for broker credential id {} (broker={})", (force ? "✅ Scheduled" : "✅"), creds.getId(), brokerEnum.getDisplayName());
            } else {
                log.warn("Provider returned no token for credential id {} (broker={})", creds.getId(), brokerEnum.getDisplayName());
            }
        } catch (Exception e) {
            log.error("Failed to refresh token for broker credential id {} (broker={}): {}", creds.getId(), brokerEnum.getDisplayName(), e.getMessage(), e);
        }
    }
}
