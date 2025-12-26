package org.com.sharekhan.service;

import org.com.sharekhan.entity.UserConfig;
import org.com.sharekhan.repository.UserConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserConfigService {
    @Autowired
    private UserConfigRepository repository;

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
}
