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
}
