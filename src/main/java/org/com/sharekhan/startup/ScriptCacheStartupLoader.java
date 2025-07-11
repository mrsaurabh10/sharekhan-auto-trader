package org.com.sharekhan.startup;

import com.sharekhan.http.exceptions.SharekhanAPIException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ScriptMasterCacheService;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")  // exclude from test profile
public class ScriptCacheStartupLoader {

    private final ScriptMasterCacheService scriptService;
    private final ScriptMasterRepository repository;

    @PostConstruct
    public void loadScriptsIfEmpty() {
        try {
            if (repository.count() == 0) {
                log.info("📦 DB is empty. Loading script master cache from Sharekhan...");
                Map<String, JSONObject> cache = scriptService.getScriptCache("NF");
                cache = scriptService.getScriptCache("NC");
                cache = scriptService.getScriptCache("BF");
                cache = scriptService.getScriptCache("BC");
                cache = scriptService.getScriptCache("MX");
            } else {
                log.info("✅ Script master already present in DB. Skipping fetch.");
            }
        } catch (Exception e) {
            log.error("❌ Failed to load script master cache at startup: {}", e.getMessage(), e);
        } catch (SharekhanAPIException e) {
            throw new RuntimeException(e);
        }
    }
}
