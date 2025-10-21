package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.enums.Broker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.test-auth-providers", havingValue = "true")
@RequiredArgsConstructor
public class TestAuthProvidersRunner implements CommandLineRunner {

    private final BrokerAuthProviderRegistry registry;
    private final TokenStoreService tokenStoreService;

    @Override
    public void run(String... args) throws Exception {
        log.info("🔧 TestAuthProvidersRunner enabled - attempting to fetch tokens for all providers");
        for (BrokerAuthProvider provider : registry.getAllProviders()) {
            Broker broker = provider.getBroker();
            try {
                AuthTokenResult result = provider.loginAndFetchToken();
                tokenStoreService.updateToken(broker, result.token(), result.expiresIn());
                log.info("✅ Fetched and stored token for {} (expiresIn={}s)", broker, result.expiresIn());
            } catch (Exception e) {
                log.error("❌ Failed to fetch token for {}: {}", broker, e.getMessage(), e);
            }
        }
        log.info("🔧 TestAuthProvidersRunner finished");
    }
}

