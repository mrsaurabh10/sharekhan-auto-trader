package org.com.sharekhan.auth;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStoreService {

    private final AccessTokenRepository repository;

    @Getter
    private String accessToken;
    private Instant expiry;

    @PostConstruct
    public void loadFromDb() {
        AccessTokenEntity latest = repository.findTopByOrderByExpiryDesc();
        if (latest != null && Instant.now().isBefore(latest.getExpiry())) {
            this.accessToken = latest.getToken();
            this.expiry = latest.getExpiry();
            log.info("🔐 Loaded access token from DB. Expiry: {}", expiry);
        }
    }

    public void updateToken(String token, long expiresInSeconds) {
        this.accessToken = token;
        this.expiry = Instant.now().plusSeconds(expiresInSeconds - 60);
        log.info("🔐 Access token updated. New expiry: {}", expiry);

        repository.deleteAll();
        repository.save(AccessTokenEntity.builder()
                .token(token)
                .expiry(this.expiry)
                .build());
    }

    public boolean isExpired() {
        return accessToken == null || expiry == null || Instant.now().isAfter(expiry);
    }

    public String getValidTokenOrNull() {
        return isExpired() ? null : accessToken;
    }

    public void clear() {
        this.accessToken = null;
        this.expiry = null;
        repository.deleteAll();
        log.info("🔐 Access token cleared.");
    }
}
