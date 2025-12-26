package org.com.sharekhan.repository;

import org.com.sharekhan.entity.UserConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserConfigRepository extends JpaRepository<UserConfig, Long> {
    Optional<UserConfig> findByUserIdAndKeyName(String userId, String keyName);
    List<UserConfig> findAllByUserIdAndEnabledTrue(String userId);

    // New: appUserId-based lookups (preferred)
    Optional<UserConfig> findByAppUserIdAndKeyName(Long appUserId, String keyName);
    List<UserConfig> findAllByAppUserIdAndEnabledTrue(Long appUserId);
}
