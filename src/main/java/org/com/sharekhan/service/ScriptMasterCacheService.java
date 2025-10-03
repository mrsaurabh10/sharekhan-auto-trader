package org.com.sharekhan.service;

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

        String token = tokenStoreService.getValidTokenOrNull();
        if (token == null) {
            var result = tokenLoginAutomationService.loginAndFetchToken();
            tokenStoreService.updateToken(result.token(), result.expiresIn());
            token = result.token();
        }
        if (token == null) {
            throw new IllegalStateException("Access token is not available or expired");
        }

        SharekhanConnect sdk = new SharekhanConnect(null, apiKey, token);
        JSONObject response = sdk.getActiveScript(exchange);
        JSONArray data = response.getJSONArray("data");

        int batchSize = 100;
        List<ScriptMasterEntity> batchList = new ArrayList<>(batchSize);

        for (int i = 0; i < data.length(); i++) {
            JSONObject script = data.getJSONObject(i);
            batchList.add(convertToEntity(script, exchange));

            if (batchList.size() == batchSize || i == data.length() - 1) {
                repository.saveAll(batchList);
                repository.flush();  // Optionally flush if JPA
                batchList.clear();
            }
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
