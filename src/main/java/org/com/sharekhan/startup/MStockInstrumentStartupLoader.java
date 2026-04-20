package org.com.sharekhan.startup;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.service.MStockInstrumentCacheService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class MStockInstrumentStartupLoader {

    private final MStockInstrumentCacheService cacheService;

    @PostConstruct
    public void populateInstrumentMaster() {
        try {
            boolean refreshed = cacheService.refreshInstrumentMasterIfEmpty();
            if (refreshed) {
                log.info("✅ MStock instrument master populated at startup.");
            }
        } catch (Exception ex) {
            log.error("❌ Failed to populate MStock instrument master at startup: {}", ex.getMessage(), ex);
        }
    }
}
