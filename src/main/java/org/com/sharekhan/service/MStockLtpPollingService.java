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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockLtpPollingService {

    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockLtpService mStockLtpService;
    private final LtpCacheService ltpCacheService;
    private final PriceTriggerService priceTriggerService;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
    private static final Map<String, String> EXCHANGE_CODE_OVERRIDES = Map.of(
            "NC", "NSE",
            "BC", "BSE",
            "NF", "NFO",
            "BF", "BFO"
    );

    // Cache to avoid hitting DB every 500ms for mapping scripCode -> MStockKey
    private final Map<Integer, String> scripCodeToMStockKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> mStockKeyToScripCodeCache = new ConcurrentHashMap<>();
    private final AtomicBoolean afterHoursLogged = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 500)
    public void pollMStockLtp() {
        try {
            ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
            LocalTime currentTime = now.toLocalTime();
            if (!currentTime.isBefore(MARKET_CLOSE_TIME)) {
                if (afterHoursLogged.compareAndSet(false, true)) {
                    log.info("Skipping MStock LTP polling after market close ({} IST)", currentTime);
                }
                return;
            } else {
                afterHoursLogged.set(false);
            }

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
        String cached = scripCodeToMStockKeyCache.get(scripCode);
        if (cached != null && isNormalizedKey(cached)) {
            return cached;
        }

        if (cached != null) {
            scripCodeToMStockKeyCache.remove(scripCode);
            mStockKeyToScripCodeCache.remove(cached);
        }

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
        if (script != null) {
            String exchange = normalizeExchange(script.getExchange());
            String tradingSymbol = normalizeTradingSymbol(script.getTradingSymbol());
            if (exchange == null || tradingSymbol == null) {
                return null;
            }

            String mstockKey = exchange + ":" + tradingSymbol;
            scripCodeToMStockKeyCache.put(scripCode, mstockKey);
            mStockKeyToScripCodeCache.put(mstockKey, scripCode);
            return mstockKey;
        }

        return null;
    }

    private String normalizeExchange(String exchange) {
        if (!StringUtils.hasText(exchange)) return null;
        String normalized = exchange.trim().toUpperCase(Locale.ROOT);
        return EXCHANGE_CODE_OVERRIDES.getOrDefault(normalized, normalized);
    }

    private String normalizeTradingSymbol(String tradingSymbol) {
        if (!StringUtils.hasText(tradingSymbol)) return null;
        return tradingSymbol.trim();
    }

    private boolean isNormalizedKey(String key) {
        if (!StringUtils.hasText(key)) return false;
        int idx = key.indexOf(':');
        if (idx <= 0) return false;
        String exchange = key.substring(0, idx).toUpperCase(Locale.ROOT);
        return !EXCHANGE_CODE_OVERRIDES.containsKey(exchange);
    }
}
