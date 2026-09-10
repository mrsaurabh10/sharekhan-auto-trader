package org.com.sharekhan.startup;

import com.sharekhan.http.exceptions.SharekhanAPIException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ScriptMasterCacheService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class ScriptCacheStartupLoader {
    private static final List<String> EXCHANGES = List.of("NF", "NC", "BF", "BC", "MX");
    private final ScriptMasterCacheService scriptService;
    private final ScriptMasterRepository repository;
    private final Set<String> pendingExchanges = new LinkedHashSet<>();
    private boolean ready;
    private boolean initialized;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void loadScriptsIfEmpty() {
        ready = true;
        refreshPendingExchanges();
    }

    @Scheduled(fixedDelayString = "${app.script-master.retry-delay-ms:60000}")
    public synchronized void retryPendingExchanges() {
        if (ready) refreshPendingExchanges();
    }

    private void refreshPendingExchanges() {
        if (!initialized) {
            try {
                if (repository.count() == 0 || repository.existsByTickSizeIsNull()) {
                    pendingExchanges.addAll(EXCHANGES);
                } else {
                    // A prior process may have stopped after loading only some exchanges.
                    List<String> present = repository.findDistinctExchanges();
                    EXCHANGES.stream().filter(exchange -> present.stream().noneMatch(exchange::equalsIgnoreCase))
                            .forEach(pendingExchanges::add);
                }
                initialized = true;
            } catch (Exception e) {
                log.warn("Unable to inspect script master; will retry: {}", e.getMessage());
                return;
            }
        }
        for (String exchange : List.copyOf(pendingExchanges)) {
            try {
                scriptService.getScriptCache(exchange);
                pendingExchanges.remove(exchange);
                log.info("Sharekhan script master loaded for {}", exchange);
            } catch (Exception | SharekhanAPIException e) {
                log.warn("Sharekhan script master unavailable for {}; keeping existing rows and retrying later: {}",
                        exchange, e.getMessage());
            }
        }
    }
}
