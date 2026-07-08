package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.UserConfig;
import org.com.sharekhan.repository.UserConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserConfigService {
    private final UserConfigRepository repository;

    // ---------------- Legacy String userId methods (kept for backward compatibility) ----------------
    public void setConfig(String userId, String key, String value, boolean enabled) {
        UserConfig config = repository.findByUserIdAndKeyName(userId, key)
                .orElse(new UserConfig());
        config.setUserId(userId);
        config.setKeyName(key);
        config.setValue(value);
        config.setEnabled(enabled);
        repository.save(config);
    }

    public String getConfig(String userId, String key, String defaultValue) {
        return repository.findByUserIdAndKeyName(userId, key)
                .filter(UserConfig::isEnabled)
                .map(UserConfig::getValue)
                .orElse(defaultValue);
    }

    public List<UserConfig> getActiveConfigs(String userId) {
        return repository.findAllByUserIdAndEnabledTrue(userId);
    }

    // ---------------- Preferred Long appUserId methods ----------------
    public void setConfig(Long appUserId, String key, String value, boolean enabled) {
        if (appUserId == null) {
            // no appUserId present, no-op to avoid creating orphan rows
            return;
        }
        UserConfig config = repository.findByAppUserIdAndKeyName(appUserId, key)
                .orElse(new UserConfig());
        config.setAppUserId(appUserId);
        config.setKeyName(key);
        config.setValue(value);
        config.setEnabled(enabled);
        repository.save(config);
    }

    public String getConfig(Long appUserId, String key, String defaultValue) {
        if (appUserId == null) return defaultValue;
        return repository.findByAppUserIdAndKeyName(appUserId, key)
                .filter(UserConfig::isEnabled)
                .map(UserConfig::getValue)
                .orElse(defaultValue);
    }

    public List<UserConfig> getActiveConfigs(Long appUserId) {
        if (appUserId == null) return List.of();
        return repository.findAllByAppUserIdAndEnabledTrue(appUserId);
    }

    public List<UserConfig> getConfigs(Long appUserId) {
        if (appUserId == null) return List.of();
        return repository.findAllByAppUserIdOrderByKeyNameAsc(appUserId);
    }

    @Transactional
    public UserConfig createConfig(Long appUserId, String key, String value, boolean enabled) {
        if (appUserId == null) throw new IllegalArgumentException("appUserId required");
        String normalizedKey = normalizeKey(key);
        if (repository.findByAppUserIdAndKeyName(appUserId, normalizedKey).isPresent()) {
            throw new IllegalArgumentException("configuration key already exists");
        }
        UserConfig config = new UserConfig();
        config.setAppUserId(appUserId);
        config.setKeyName(normalizedKey);
        config.setValue(normalizeValue(value));
        config.setEnabled(enabled);
        return repository.save(config);
    }

    @Transactional
    public UserConfig updateConfig(Long appUserId, Long configId, String key, String value, boolean enabled) {
        if (appUserId == null || configId == null) throw new IllegalArgumentException("user and configuration required");
        UserConfig config = repository.findById(configId)
                .filter(existing -> appUserId.equals(existing.getAppUserId()))
                .orElseThrow(() -> new IllegalArgumentException("configuration not found"));
        String normalizedKey = normalizeKey(key);
        repository.findByAppUserIdAndKeyName(appUserId, normalizedKey)
                .filter(existing -> !configId.equals(existing.getId()))
                .ifPresent(existing -> { throw new IllegalArgumentException("configuration key already exists"); });
        config.setKeyName(normalizedKey);
        config.setValue(normalizeValue(value));
        config.setEnabled(enabled);
        return repository.save(config);
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("configuration key required");
        String normalized = key.trim();
        if (normalized.length() > 255) throw new IllegalArgumentException("configuration key is too long");
        if (!normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("configuration key may only contain letters, numbers, dot, underscore, and hyphen");
        }
        return normalized;
    }

    private String normalizeValue(String value) {
        if (value == null) throw new IllegalArgumentException("configuration value required");
        if (value.length() > 255) throw new IllegalArgumentException("configuration value is too long");
        return value;
    }
}
