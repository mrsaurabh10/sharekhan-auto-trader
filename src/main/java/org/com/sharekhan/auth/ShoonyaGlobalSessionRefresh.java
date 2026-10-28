package org.com.sharekhan.auth;

import java.nio.file.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Morning maintenance for the configured global feed, independent of customer accounts. */
@Service @RequiredArgsConstructor @Slf4j
public class ShoonyaGlobalSessionRefresh {
    private final AccessTokenRepository tokens;
    private final ShoonyaProperties properties;
    private final BrokerAuthProviderRegistry providers;
    private final TokenStoreService store;
    @Value("${app.shoonya.session-refresh.enabled:true}") private boolean enabled = true;
    @Value("${app.shoonya.session-refresh.minimum-available-mib:450}") private long minimumAvailableMiB = 450;

    // Avoid the existing 07:30/08:30 customer login batches. Success makes later slots no-ops.
    @Scheduled(cron = "0 40,50,55 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduled() { maintain(LocalDateTime.now(ZoneId.of("Asia/Kolkata"))); }

    synchronized void maintain(LocalDateTime now) {
        if (!enabled || now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY
                || now.toLocalTime().isBefore(LocalTime.of(8,40)) || !now.toLocalTime().isBefore(LocalTime.of(9,0))) return;
        try {
            if (ShoonyaSavedSession.currentGlobal(tokens.findAllByBrokerName("Shoonya"), now,
                    properties.getAccessTokenTtlSeconds()).isPresent()) {
                log.info("SHOONYA_GLOBAL_SESSION | status=current_day_saved; login skipped");
                return;
            }
            if (availableMemoryMiB() < Math.max(450, minimumAvailableMiB)) {
                log.warn("SHOONYA_GLOBAL_SESSION | status=deferred_memory; reconnect required if morning retries fail");
                return;
            }
            var provider = providers.getProvider(Broker.SHOONYA);
            if (provider == null) throw new IllegalStateException();
            var result = provider.loginAndFetchToken();
            if (result == null || result.token() == null || result.token().isBlank() || result.expiresIn() <= 60
                    || result.expiresIn() != properties.getAccessTokenTtlSeconds()) throw new IllegalStateException();
            store.updateToken(Broker.SHOONYA, result.token(), result.expiresIn());
            log.info("SHOONYA_GLOBAL_SESSION | status=refreshed");
        } catch (Exception failure) {
            // Browser/provider exceptions may contain credentials or authorization codes.
            log.warn("SHOONYA_GLOBAL_SESSION | status=failed; type={}; reconnect required if morning retries fail",
                    failure.getClass().getSimpleName());
        }
    }

    long availableMemoryMiB() {
        try (var lines = Files.lines(Path.of("/proc/meminfo"))) {
            return lines.filter(s -> s.startsWith("MemAvailable:"))
                    .mapToLong(s -> Long.parseLong(s.trim().split("\\s+")[1]) / 1024).findFirst().orElse(-1);
        } catch (Exception unavailable) { return -1; }
    }
}
