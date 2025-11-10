package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccessTokenPersistenceService {

    private final AccessTokenRepository repository;
    private final CryptoService cryptoService;

    @Transactional(readOnly = true)
    public AccessTokenEntity findLatestByBroker(String brokerDisplayName) {
        AccessTokenEntity e = repository.findTopByBrokerNameOrderByExpiryDesc(brokerDisplayName);
        if (e != null && e.getToken() != null) {
            try { e.setToken(cryptoService.decrypt(e.getToken())); } catch (Exception ignored) {}
        }
        return e;
    }

    @Transactional(readOnly = true)
    public Optional<AccessTokenEntity> findLatestByBrokerAndCustomer(String brokerDisplayName, Long customerId) {
        java.util.List<AccessTokenEntity> all = repository.findAllByBrokerNameAndCustomerId(brokerDisplayName, customerId);
        if (all == null || all.isEmpty()) return Optional.empty();
        AccessTokenEntity latest = all.stream().max(java.util.Comparator.comparing(AccessTokenEntity::getExpiry)).orElse(null);
        if (latest != null && latest.getToken() != null) {
            try { latest.setToken(cryptoService.decrypt(latest.getToken())); } catch (Exception ignored) {}
        }
        return Optional.ofNullable(latest);
    }

    @Transactional
    public void replaceToken(String brokerDisplayName, String token, Instant expiry) {
        repository.deleteAllByBrokerName(brokerDisplayName);
        String encrypted = token != null ? cryptoService.encrypt(token) : null;
        repository.save(AccessTokenEntity.builder()
                .token(encrypted)
                .expiry(expiry)
                .brokerName(brokerDisplayName)
                .build());
    }

    // existing API kept for compatibility
    @Transactional
    public void replaceTokenForCustomer(String brokerDisplayName, Long customerId, String token, Instant expiry) {
        replaceTokenForCustomer(brokerDisplayName, customerId, null, token, expiry);
    }

    // New overload: allow persisting userId (app_user.id) alongside the broker-specific customerId
    @Transactional
    public void replaceTokenForCustomer(String brokerDisplayName, Long customerId, Long userId, String token, Instant expiry) {
        java.util.List<AccessTokenEntity> existing = repository.findAllByBrokerNameAndCustomerId(brokerDisplayName, customerId);
        if (existing != null && !existing.isEmpty()) repository.deleteAll(existing);
        String encrypted = token != null ? cryptoService.encrypt(token) : null;
        AccessTokenEntity ent = AccessTokenEntity.builder()
                .token(encrypted)
                .expiry(expiry)
                .brokerName(brokerDisplayName)
                .customerId(customerId)
                .userId(userId)
                .build();
        repository.save(ent);
    }

    @Transactional
    public void deleteByBroker(String brokerDisplayName) {
        repository.deleteAllByBrokerName(brokerDisplayName);
    }
}
