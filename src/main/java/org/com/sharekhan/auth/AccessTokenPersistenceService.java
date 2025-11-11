package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccessTokenPersistenceService {

    private final AccessTokenRepository repository;
    private final CryptoService cryptoService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @Transactional(readOnly = true)
    public AccessTokenEntity findLatestByBroker(String brokerDisplayName) {
        AccessTokenEntity e = repository.findTopByBrokerNameOrderByExpiryDesc(brokerDisplayName);
        if (e != null && e.getToken() != null) {
            try { e.setToken(cryptoService.decrypt(e.getToken())); } catch (Exception ignored) {}
        }
        return e;
    }

    @Transactional(readOnly = true)
    public Optional<AccessTokenEntity> findLatestByBrokerAndBrokerCredentialsId(String brokerDisplayName, Long brokerCredentialsId) {
        java.util.List<AccessTokenEntity> all = repository.findAllByBrokerNameAndBrokerCredentialsId(brokerDisplayName, brokerCredentialsId);
        if (all == null || all.isEmpty()) return Optional.empty();
        AccessTokenEntity latest = all.stream().max(java.util.Comparator.comparing(AccessTokenEntity::getExpiry)).orElse(null);
        if (latest != null && latest.getToken() != null) {
            try { latest.setToken(cryptoService.decrypt(latest.getToken())); } catch (Exception ignored) {}
        }
        return Optional.ofNullable(latest);
    }

    @Transactional(readOnly = true)
    public Optional<AccessTokenEntity> findLatestByBrokerAndUserId(String brokerDisplayName, Long userId) {
        java.util.List<AccessTokenEntity> all = repository.findAllByBrokerNameAndUserId(brokerDisplayName, userId);
        if (all == null || all.isEmpty()) return Optional.empty();
        AccessTokenEntity latest = all.stream().max(java.util.Comparator.comparing(AccessTokenEntity::getExpiry)).orElse(null);
        if (latest != null && latest.getToken() != null) {
            try { latest.setToken(cryptoService.decrypt(latest.getToken())); } catch (Exception ignored) {}
        }
        return Optional.ofNullable(latest);
    }

    @Transactional(readOnly = true)
    public Optional<AccessTokenEntity> findLatestByBrokerAndCustomer(String brokerDisplayName, Long customerId) {
        if (customerId == null) return Optional.empty();
        // find BrokerCredentials rows for this broker/customer combo
        List<org.com.sharekhan.entity.BrokerCredentialsEntity> creds = brokerCredentialsRepository.findAllByBrokerNameAndCustomerId(brokerDisplayName, customerId);
        if (creds == null || creds.isEmpty()) return Optional.empty();
        List<AccessTokenEntity> tokens = new ArrayList<>();
        for (var c : creds) {
            var list = repository.findAllByBrokerNameAndBrokerCredentialsId(brokerDisplayName, c.getId());
            if (list != null && !list.isEmpty()) tokens.addAll(list);
        }
        if (tokens.isEmpty()) return Optional.empty();
        AccessTokenEntity latest = tokens.stream().max(java.util.Comparator.comparing(AccessTokenEntity::getExpiry)).orElse(null);
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

    // Per-broker-credential storage
    @Transactional
    public void replaceTokenForBrokerCredentials(String brokerDisplayName, Long brokerCredentialsId, String token, Instant expiry) {
        if (brokerCredentialsId == null) return;
        var existing = repository.findAllByBrokerNameAndBrokerCredentialsId(brokerDisplayName, brokerCredentialsId);
        if (existing != null && !existing.isEmpty()) repository.deleteAll(existing);
        String encrypted = token != null ? cryptoService.encrypt(token) : null;
        AccessTokenEntity ent = AccessTokenEntity.builder()
                .token(encrypted)
                .expiry(expiry)
                .brokerName(brokerDisplayName)
                .brokerCredentialsId(brokerCredentialsId)
                .build();
        repository.save(ent);
    }

    // Per-customer convenience: map customerId -> brokerCredentials and persist token for each matching credentials row
    @Transactional
    public void replaceTokenForCustomer(String brokerDisplayName, Long customerId, Long userId, String token, Instant expiry) {
        if (customerId == null) return;
        List<org.com.sharekhan.entity.BrokerCredentialsEntity> creds = brokerCredentialsRepository.findAllByBrokerNameAndCustomerId(brokerDisplayName, customerId);
        if (creds == null || creds.isEmpty()) {
            // no broker credentials rows found; fallback to storing by userId
            replaceTokenForUser(brokerDisplayName, userId, token, expiry);
            return;
        }
        for (var c : creds) {
            // remove existing tokens for that broker-credentials id
            var existing = repository.findAllByBrokerNameAndBrokerCredentialsId(brokerDisplayName, c.getId());
            if (existing != null && !existing.isEmpty()) repository.deleteAll(existing);
            String encrypted = token != null ? cryptoService.encrypt(token) : null;
            AccessTokenEntity ent = AccessTokenEntity.builder()
                    .token(encrypted)
                    .expiry(expiry)
                    .brokerName(brokerDisplayName)
                    .brokerCredentialsId(c.getId())
                    .userId(userId)
                    .build();
            repository.save(ent);
        }
    }

    // Per-user storage (app user id)
    @Transactional
    public void replaceTokenForUser(String brokerDisplayName, Long userId, String token, Instant expiry) {
        if (userId == null) return;
        java.util.List<AccessTokenEntity> existing = repository.findAllByBrokerNameAndUserId(brokerDisplayName, userId);
        if (existing != null && !existing.isEmpty()) repository.deleteAll(existing);
        String encrypted = token != null ? cryptoService.encrypt(token) : null;
        AccessTokenEntity ent = AccessTokenEntity.builder()
                .token(encrypted)
                .expiry(expiry)
                .brokerName(brokerDisplayName)
                .userId(userId)
                .build();
        repository.save(ent);
    }

    @Transactional
    public void deleteByBroker(String brokerDisplayName) {
        repository.deleteAllByBrokerName(brokerDisplayName);
    }
}
