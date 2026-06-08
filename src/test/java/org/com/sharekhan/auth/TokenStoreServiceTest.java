package org.com.sharekhan.auth;

import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.BrokerCredentialsService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TokenStoreServiceTest {

    @Test
    void refreshTokenUsesCredentialScopedProviderAndUpdatesCustomerToken() {
        BrokerCredentialsEntity credentials = BrokerCredentialsEntity.builder()
                .id(55L)
                .brokerName(Broker.MSTOCK.getDisplayName())
                .customerId(1234L)
                .appUserId(7L)
                .apiKey("encrypted-api-key")
                .build();
        RecordingPersistenceService persistenceService = new RecordingPersistenceService();
        RecordingAuthProvider authProvider = new RecordingAuthProvider(credentials);

        TokenStoreService tokenStoreService = new TokenStoreService(
                persistenceService,
                new BrokerAuthProviderRegistry(List.of(authProvider)),
                new StaticBrokerCredentialsService(credentials),
                appUserRepository(),
                brokerCredentialsRepository(credentials)
        );
        TokenStoreService.TokenInfo failedToken = new TokenStoreService.TokenInfo(
                "old-token",
                "encrypted-api-key",
                1234L,
                7L,
                55L
        );

        TokenStoreService.TokenInfo refreshed = tokenStoreService.refreshToken(Broker.MSTOCK, failedToken);

        assertNotNull(refreshed);
        assertEquals("new-token", refreshed.getToken());
        assertEquals("encrypted-api-key", refreshed.getApiKey());
        assertEquals(1234L, refreshed.getCustomerId());
        assertEquals(7L, refreshed.getAppUserId());
        assertEquals(55L, refreshed.getBrokerCredentialsId());
        assertEquals("new-token", tokenStoreService.getAccessToken(Broker.MSTOCK, 1234L));

        assertSame(credentials, authProvider.lastCredentials);
        assertEquals(0, authProvider.globalLoginCalls);
        assertEquals(Broker.MSTOCK.getDisplayName(), persistenceService.brokerName);
        assertEquals(1234L, persistenceService.customerId);
        assertEquals(7L, persistenceService.userId);
        assertEquals("new-token", persistenceService.token);
        assertNotNull(persistenceService.expiry);
    }

    private static BrokerCredentialsRepository brokerCredentialsRepository(BrokerCredentialsEntity credentials) {
        return (BrokerCredentialsRepository) Proxy.newProxyInstance(
                BrokerCredentialsRepository.class.getClassLoader(),
                new Class<?>[]{BrokerCredentialsRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        Long id = (Long) args[0];
                        return credentials.getId().equals(id) ? Optional.of(credentials) : Optional.empty();
                    }
                    if ("toString".equals(method.getName())) {
                        return "BrokerCredentialsRepositoryFake";
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static AppUserRepository appUserRepository() {
        return (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == List.class) return List.of();
        if (returnType == Optional.class) return Optional.empty();
        return null;
    }

    private static final class RecordingPersistenceService extends AccessTokenPersistenceService {
        private String brokerName;
        private Long customerId;
        private Long userId;
        private String token;
        private Instant expiry;

        private RecordingPersistenceService() {
            super(null, null, null);
        }

        @Override
        public Optional<AccessTokenEntity> findLatestByBrokerAndCustomer(String brokerDisplayName, Long customerId) {
            return Optional.empty();
        }

        @Override
        public void replaceTokenForCustomer(String brokerDisplayName, Long customerId, Long userId, String token, Instant expiry) {
            this.brokerName = brokerDisplayName;
            this.customerId = customerId;
            this.userId = userId;
            this.token = token;
            this.expiry = expiry;
        }
    }

    private static final class StaticBrokerCredentialsService extends BrokerCredentialsService {
        private final BrokerCredentialsEntity credentials;

        private StaticBrokerCredentialsService(BrokerCredentialsEntity credentials) {
            super(null);
            this.credentials = credentials;
        }

        @Override
        public Optional<BrokerCredentialsEntity> findForBrokerAndCustomer(String brokerName, Long customerId) {
            if (credentials.getBrokerName().equals(brokerName) && credentials.getCustomerId().equals(customerId)) {
                return Optional.of(credentials);
            }
            return Optional.empty();
        }
    }

    private static final class RecordingAuthProvider implements BrokerAuthProvider {
        private final BrokerCredentialsEntity expectedCredentials;
        private BrokerCredentialsEntity lastCredentials;
        private int globalLoginCalls;

        private RecordingAuthProvider(BrokerCredentialsEntity expectedCredentials) {
            this.expectedCredentials = expectedCredentials;
        }

        @Override
        public Broker getBroker() {
            return Broker.MSTOCK;
        }

        @Override
        public AuthTokenResult loginAndFetchToken() {
            globalLoginCalls++;
            return new AuthTokenResult("global-token", 3600);
        }

        @Override
        public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
            lastCredentials = creds;
            assertSame(expectedCredentials, creds);
            return new AuthTokenResult("new-token", 3600);
        }
    }
}
