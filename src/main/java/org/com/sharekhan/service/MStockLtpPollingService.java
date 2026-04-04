package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockLtpPollingService {

    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockLtpService mStockLtpService;
    private final LtpCacheService ltpCacheService;
    private final PriceTriggerService priceTriggerService;

    // Cache to avoid hitting DB every 500ms for mapping scripCode -> MStockKey
    private final Map<Integer, String> scripCodeToMStockKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> mStockKeyToScripCodeCache = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 500)
    public void pollMStockLtp() {
        try {
            Set<String> activeScripKeys = webSocketSubscriptionService.getActiveScripKeys();
            if (activeScripKeys == null || activeScripKeys.isEmpty()) {
                return;
            }

            List<String> mstockInstruments = new ArrayList<>();
            for (String scripKey : activeScripKeys) {
                Integer scripCode = extractScripCode(scripKey);
                if (scripCode != null) {
                    String mstockKey = getMStockKey(scripCode);
                    if (mstockKey != null) {
                        mstockInstruments.add(mstockKey);
                    }
                }
            }

            if (mstockInstruments.isEmpty()) {
                return;
            }

            // Batch fetch LTP from MStock
            Map<String, Map<String, Object>> ltpData = mStockLtpService.fetchLtp(mstockInstruments);
            if (ltpData == null || ltpData.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Map<String, Object>> entry : ltpData.entrySet()) {
                String mstockKey = entry.getKey();
                Map<String, Object> data = entry.getValue();

                if (data != null && data.get("last_price") != null) {
                    Object priceObj = data.get("last_price");
                    if (priceObj instanceof Number) {
                        double newLtp = ((Number) priceObj).doubleValue();
                        Integer scripCode = mStockKeyToScripCodeCache.get(mstockKey);

                        if (scripCode != null) {
                            Double cachedLtp = ltpCacheService.getLtp(scripCode);

                            // Only update and trigger evaluation if LTP has changed
                            if (cachedLtp == null || cachedLtp != newLtp) {
                                ltpCacheService.updateLtp(scripCode, newLtp);
                                
                                // Fire triggers with updated LTP
                                priceTriggerService.evaluatePriceTrigger(scripCode, newLtp);
                                priceTriggerService.monitorOpenTrades(scripCode, newLtp);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error during MStock LTP polling: {}", e.getMessage());
            log.trace("MStock LTP polling error trace", e);
        }
    }

    private Integer extractScripCode(String scripKey) {
        if (!StringUtils.hasText(scripKey)) return null;
        try {
            String codeStr = scripKey.replaceAll("[^0-9]", "");
            return Integer.parseInt(codeStr);
        } catch (Exception e) {
            return null;
        }
    }

    private String getMStockKey(Integer scripCode) {
        if (scripCodeToMStockKeyCache.containsKey(scripCode)) {
            return scripCodeToMStockKeyCache.get(scripCode);
        }

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (script != null) {
            String mstockKey = script.getExchange() + ":" + script.getTradingSymbol();
            scripCodeToMStockKeyCache.put(scripCode, mstockKey);
            mStockKeyToScripCodeCache.put(mstockKey, scripCode);
            return mstockKey;
        }

        return null;
    }
}
