package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.enums.Broker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;

    @Scheduled(fixedDelay = 3600_000) // every hour
    public void refreshTokenIfNeeded() {
        for (BrokerAuthProvider provider : providerRegistry.getAllProviders()) {
            Broker broker = provider.getBroker();
            if (tokenStoreService.isExpired(broker)) {
                log.info("🔁 Access token expired for {}. Re-authenticating...", broker);
                try {
                    AuthTokenResult result = provider.loginAndFetchToken();
                    tokenStoreService.updateToken(broker, result.token(), result.expiresIn());
                    log.info("✅ Access token refreshed for {}.", broker);
                } catch (Exception e) {
                    log.error("❌ Failed to refresh token for {}: {}", broker, e.getMessage(), e);
                }
            }
        }
    }


    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshTokenAt830IST() {
        log.info("⏰ Scheduled 8:30 AM IST token refresh starting...");

        for (BrokerAuthProvider provider : providerRegistry.getAllProviders()) {
            Broker broker = provider.getBroker();
            try {
                AuthTokenResult result = provider.loginAndFetchToken();
                tokenStoreService.updateToken(broker, result.token(), result.expiresIn());
                log.info("✅ Token refreshed successfully for {} at 8:30 AM IST.", broker);
            } catch (Exception e) {
                log.error("❌ Failed scheduled token refresh at 8:30 AM IST for {}: {}", broker, e.getMessage(), e);
            }
        }
    }
}
