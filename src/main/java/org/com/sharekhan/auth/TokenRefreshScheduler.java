package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private final TokenStoreService tokenStoreService;
    private final TokenLoginAutomationService tokenLoginAutomationService;

    @Scheduled(fixedDelay = 3600_000) // every 5 minutes
    public void refreshTokenIfNeeded() {
        if (tokenStoreService.isExpired()) {
            log.info("🔁 Access token expired. Re-authenticating...");

            try {
                // 🔐 This calls your automation (e.g., Playwright-based login)
                TokenLoginAutomationService.TokenResult result = tokenLoginAutomationService.loginAndFetchToken();

                tokenStoreService.updateToken(result.token(), result.expiresIn());
                log.info("✅ Access token refreshed.");
            } catch (Exception e) {
                log.error("❌ Failed to refresh token: {}", e.getMessage(), e);
            }
        }
    }
}
