package org.com.sharekhan.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.json.JSONArray;
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
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScriptMasterCacheService {

    private final Map<String, JSONObject> scriptCache = new HashMap<>();
    private final ScriptMasterRepository repository;
    private final TokenStoreService tokenStoreService;
    private final TokenLoginAutomationService tokenLoginAutomationService;


    private static final String apiKey = "M57X7RqA9C43IOq8iJSySWv8LAD2DzkM";

    @Autowired
    public ScriptMasterCacheService(ScriptMasterRepository repository,
                                    TokenStoreService tokenStoreService,
                                    TokenLoginAutomationService tokenLoginAutomationService) {
        this.repository = repository;
        this.tokenStoreService = tokenStoreService;
        this.tokenLoginAutomationService = tokenLoginAutomationService;
    }

    public Map<String, JSONObject> getScriptCache(String exchange) throws IOException, SharekhanAPIException {
        if (!scriptCache.isEmpty()) return scriptCache;

        // Construct URL - replace NF with exchange
        String urlStr = "https://api.sharekhan.com/skapi/services/master/" + exchange;

        // Setup HTTP connection
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new SharekhanAPIException("Failed to fetch data, HTTP code: " + responseCode);
        }

        try (InputStream inputStream = conn.getInputStream()) {
            JsonFactory jsonFactory = new JsonFactory();
            try (JsonParser parser = jsonFactory.createParser(inputStream)) {
                List<ScriptMasterEntity> batchList = new ArrayList<>();
                int batchSize = 100;

                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Expected JSON to start with an object");
                }

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.getCurrentName();
                    if ("data".equals(fieldName)) {
                        parser.nextToken(); // move to start array
                        if (parser.currentToken() != JsonToken.START_ARRAY) {
                            throw new IOException("'data' field is not an array");
                        }
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            JSONObject scriptJson = new JSONObject(parser.readValueAsTree().toString());
                            ScriptMasterEntity entity = convertToEntity(scriptJson, exchange);
                            batchList.add(entity);

                            if (batchList.size() == batchSize) {

                                repository.saveAll(batchList);
                                repository.flush();
                                batchList.clear();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                }

                if (!batchList.isEmpty()) {
                    repository.saveAll(batchList);
                    repository.flush();
                    batchList.clear();
                }
            }
        } finally {
            conn.disconnect();
        }

        return scriptCache;
    }


    public JSONObject getScriptBySymbol(String tradingSymbol) {
        return scriptCache.get(tradingSymbol);
    }

    public void clearCache() {
        scriptCache.clear();
        repository.deleteAll();
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
