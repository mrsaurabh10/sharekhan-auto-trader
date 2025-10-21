package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AccessTokenPersistenceService {

    private final AccessTokenRepository repository;

    @Transactional(readOnly = true)
    public AccessTokenEntity findLatestByBroker(String brokerDisplayName) {
        return repository.findTopByBrokerNameOrderByExpiryDesc(brokerDisplayName);
    }

    @Transactional
    public void replaceToken(String brokerDisplayName, String token, Instant expiry) {
        repository.deleteAllByBrokerName(brokerDisplayName);
        repository.save(AccessTokenEntity.builder()
                .token(token)
                .expiry(expiry)
                .brokerName(brokerDisplayName)
                .build());
    }

    @Transactional
    public void deleteByBroker(String brokerDisplayName) {
        repository.deleteAllByBrokerName(brokerDisplayName);
    }
}
