package org.com.sharekhan.service;

import org.com.sharekhan.entity.UserConfig;
import org.com.sharekhan.repository.UserConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserConfigServiceTest {

    private UserConfigRepository repository;
    private UserConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserConfigRepository.class);
        service = new UserConfigService(repository);
    }

    @Test
    void createConfigStoresTrimmedKeyValueAndEnabledState() {
        when(repository.findByAppUserIdAndKeyName(7L, "max_amount_per_trade")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(UserConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserConfig result = service.createConfig(7L, "  max_amount_per_trade  ", "30000", false);

        ArgumentCaptor<UserConfig> captor = ArgumentCaptor.forClass(UserConfig.class);
        verify(repository).save(captor.capture());
        assertEquals(7L, result.getAppUserId());
        assertEquals("max_amount_per_trade", captor.getValue().getKeyName());
        assertEquals("30000", captor.getValue().getValue());
        assertEquals(false, captor.getValue().isEnabled());
    }

    @Test
    void createConfigRejectsDuplicateKey() {
        UserConfig existing = config(3L, 7L, "telegram_trade_enabled", "true", true);
        when(repository.findByAppUserIdAndKeyName(7L, "telegram_trade_enabled"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createConfig(7L, "telegram_trade_enabled", "false", true));

        assertEquals("configuration key already exists", error.getMessage());
    }

    @Test
    void updateConfigRejectsConfigurationOwnedByAnotherUser() {
        UserConfig existing = config(3L, 8L, "max_loss_per_trade", "8000", true);
        when(repository.findById(3L)).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig(7L, 3L, "max_loss_per_trade", "9000", true));

        assertEquals("configuration not found", error.getMessage());
    }

    @Test
    void getConfigsIncludesDisabledConfigurations() {
        List<UserConfig> configs = List.of(
                config(1L, 7L, "allow_sharekhan_research", "true", true),
                config(2L, 7L, "telegram_trade_enabled", "false", false));
        when(repository.findAllByAppUserIdOrderByKeyNameAsc(7L)).thenReturn(configs);

        List<UserConfig> result = service.getConfigs(7L);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(config -> !config.isEnabled()));
    }

    private UserConfig config(Long id, Long userId, String key, String value, boolean enabled) {
        UserConfig config = new UserConfig();
        config.setId(id);
        config.setAppUserId(userId);
        config.setKeyName(key);
        config.setValue(value);
        config.setEnabled(enabled);
        return config;
    }
}
