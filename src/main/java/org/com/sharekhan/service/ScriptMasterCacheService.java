package org.com.sharekhan.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class ScriptMasterCacheService {

    private final Map<String, Map<String, JSONObject>> scriptCacheByExchange = new HashMap<>();
    private final ScriptMasterRepository repository;


    @Autowired
    public ScriptMasterCacheService(ScriptMasterRepository repository) {
        this.repository = repository;
    }

    public synchronized Map<String, JSONObject> getScriptCache(String exchange) throws IOException, SharekhanAPIException {
        if (exchange == null || exchange.isBlank()) {
            return Map.of();
        }
        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        Map<String, JSONObject> cached = scriptCacheByExchange.get(normalizedExchange);
        if (cached != null && !cached.isEmpty()) {
            log.debug("Script cache for {} already populated ({} entries).", normalizedExchange, cached.size());
            return cached;
        }

        Map<String, JSONObject> fetched = fetchScriptsForExchange(normalizedExchange);
        scriptCacheByExchange.put(normalizedExchange, fetched);
        return fetched;
    }


    public JSONObject getScriptBySymbol(String tradingSymbol) {
        if (tradingSymbol == null) {
            return null;
        }
        for (Map<String, JSONObject> cache : scriptCacheByExchange.values()) {
            JSONObject obj = cache.get(tradingSymbol);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    public void clearCache() {
        scriptCacheByExchange.clear();
        repository.deleteAll();
    }

    public synchronized void refreshExchange(String exchange) throws IOException, SharekhanAPIException {
        if (exchange == null || exchange.isBlank()) {
            return;
        }
        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        log.info("Refreshing script master cache for exchange {}", normalizedExchange);
        scriptCacheByExchange.remove(normalizedExchange);
        Map<String, JSONObject> refreshed = fetchScriptsForExchange(normalizedExchange);
        scriptCacheByExchange.put(normalizedExchange, refreshed);
    }

    private Map<String, JSONObject> fetchScriptsForExchange(String exchange) throws IOException, SharekhanAPIException {
        log.info("Fetching script master for exchange {}", exchange);
        String urlStr = "https://api.sharekhan.com/skapi/services/master/" + exchange;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        int responseCode = conn.getResponseCode();
        log.info("HTTP GET to {} returned status {}", urlStr, responseCode);
        if (responseCode != 200) {
            throw new SharekhanAPIException("Failed to fetch data, HTTP code: " + responseCode);
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, JSONObject> exchangeMap = new HashMap<>();

        try (InputStream inputStream = conn.getInputStream();
             JsonParser parser = mapper.getFactory().createParser(inputStream)) {

            List<ScriptMasterEntity> batchList = new ArrayList<>();
            int batchSize = 200;
            int totalProcessed = 0;

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected JSON to start with an object");
            }

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.getCurrentName();
                if ("data".equals(fieldName)) {
                    parser.nextToken();
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new IOException("'data' field is not an array");
                    }
                    log.info("Parsing script data array for exchange {}", exchange);
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JSONObject scriptJson = new JSONObject(parser.readValueAsTree().toString());
                        ScriptMasterEntity entity = convertToEntity(scriptJson, exchange);
                        batchList.add(entity);
                        exchangeMap.put(entity.getTradingSymbol(), scriptJson);
                        totalProcessed++;

                        if (batchList.size() == batchSize) {
                            repository.saveAll(batchList);
                            repository.flush();
                            batchList.clear();
                            log.debug("Persisted {} scripts for {} so far", totalProcessed, exchange);
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }

            if (!batchList.isEmpty()) {
                repository.saveAll(batchList);
                repository.flush();
                log.debug("Persisted final batch of {} scripts for {}", batchList.size(), exchange);
                batchList.clear();
            }
            log.info("Stored {} scripts for exchange {}", totalProcessed, exchange);
        } finally {
            conn.disconnect();
            log.debug("Disconnected HTTP connection for exchange {}", exchange);
        }

        return exchangeMap;
    }

    public ScriptMasterEntity convertToEntity(JSONObject json, String exchange) throws SharekhanAPIException {
        return ScriptMasterEntity.builder()
                .scripCode(json.optInt("scripCode"))
                .tradingSymbol(json.optString("tradingSymbol"))
                .exchange(exchange)
                .instrumentType(json.optString("instType"))
                .strikePrice(json.has("strike") ? json.optDouble("strike") : null)
                .lotSize(json.optInt("lotSize"))
                .expiry(json.optString("expiry", null))
                .optionType(json.optString("optionType"))
                .build();
    }
}
