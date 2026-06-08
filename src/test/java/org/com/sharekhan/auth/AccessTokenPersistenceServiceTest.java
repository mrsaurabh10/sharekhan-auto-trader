package org.com.sharekhan.auth;

import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTokenPersistenceServiceTest {

    @Test
    void replaceTokenOnlyReplacesGlobalTokenRows() {
        RecordingAccessTokenRepository repository = new RecordingAccessTokenRepository();
        AccessTokenPersistenceService service = new AccessTokenPersistenceService(
                repository.proxy(),
                new PlainCryptoService(),
                brokerCredentialsRepository()
        );

        Instant expiry = Instant.parse("2026-06-08T09:15:30Z");

        service.replaceToken("MStock", "new-token", expiry);

        assertTrue(repository.deletedGlobalRowsOnly);
        assertFalse(repository.deletedAllBrokerRows);
        assertNotNull(repository.saved);
        assertEquals("encrypted:new-token", repository.saved.getToken());
        assertEquals("MStock", repository.saved.getBrokerName());
        assertEquals(expiry, repository.saved.getExpiry());
    }

    private static BrokerCredentialsRepository brokerCredentialsRepository() {
        return (BrokerCredentialsRepository) Proxy.newProxyInstance(
                BrokerCredentialsRepository.class.getClassLoader(),
                new Class<?>[]{BrokerCredentialsRepository.class},
                (proxy, method, args) -> null
        );
    }

    private static final class PlainCryptoService extends CryptoService {
        @Override
        public String encrypt(String plain) {
            return plain == null ? null : "encrypted:" + plain;
        }
    }

    private static final class RecordingAccessTokenRepository {
        private boolean deletedGlobalRowsOnly;
        private boolean deletedAllBrokerRows;
        private AccessTokenEntity saved;

        private AccessTokenRepository proxy() {
            return (AccessTokenRepository) Proxy.newProxyInstance(
                    AccessTokenRepository.class.getClassLoader(),
                    new Class<?>[]{AccessTokenRepository.class},
                    (proxy, method, args) -> {
                        if ("deleteAllByBrokerNameAndBrokerCredentialsIdIsNullAndUserIdIsNull".equals(method.getName())) {
                            deletedGlobalRowsOnly = true;
                            return null;
                        }
                        if ("deleteAllByBrokerName".equals(method.getName())) {
                            deletedAllBrokerRows = true;
                            return null;
                        }
                        if ("save".equals(method.getName())) {
                            saved = (AccessTokenEntity) args[0];
                            return saved;
                        }
                        if ("toString".equals(method.getName())) {
                            return "AccessTokenRepositoryFake";
                        }
                        return null;
                    }
            );
        }
    }
}
