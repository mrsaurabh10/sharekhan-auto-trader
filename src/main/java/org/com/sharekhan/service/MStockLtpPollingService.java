package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockLtpPollingService {

    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final MStockLtpService mStockLtpService;
    private final LtpCacheService ltpCacheService;
    private final PriceTriggerService priceTriggerService;
    private final MStockInstrumentResolver instrumentResolver;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

    private final Map<Integer, String> scripCodeToMStockKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> mStockKeyToScripCodeCache = new ConcurrentHashMap<>();
    private final AtomicBoolean afterHoursLogged = new AtomicBoolean(false);

    @Value("${app.mstock.poll-delay-ms:1500}")
    private long mstockPollDelayMs;

    @Scheduled(fixedDelayString = "${app.mstock.poll-delay-ms:1500}")
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

            java.util.LinkedHashSet<String> instrumentSet = new java.util.LinkedHashSet<>();
            for (String scripKey : activeScripKeys) {
                Integer scripCode = extractScripCode(scripKey);
                if (scripCode == null) continue;

                String mstockKey = getMStockKey(scripCode);
                if (StringUtils.hasText(mstockKey)) {
                    instrumentSet.add(mstockKey);
                }
            }

            if (instrumentSet.isEmpty()) {
                return;
            }

            Map<String, Map<String, Object>> ltpData = mStockLtpService.fetchLtp(new ArrayList<>(instrumentSet));
            if (ltpData == null || ltpData.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Map<String, Object>> entry : ltpData.entrySet()) {
                String mstockKey = entry.getKey();
                Map<String, Object> data = entry.getValue();

                if (data == null) continue;
                Object priceObj = data.get("last_price");
                if (!(priceObj instanceof Number)) continue;

                double newLtp = ((Number) priceObj).doubleValue();
                Integer scripCode = mStockKeyToScripCodeCache.get(mstockKey);
                if (scripCode == null) continue;

                Double cachedLtp = ltpCacheService.getLtp(scripCode);
                if (cachedLtp != null && Double.compare(cachedLtp, newLtp) == 0) {
                    continue;
                }

                ltpCacheService.updateLtp(scripCode, newLtp);
                priceTriggerService.evaluatePriceTrigger(scripCode, newLtp);
                priceTriggerService.monitorOpenTrades(scripCode, newLtp);
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
        Optional<String> resolved = instrumentResolver.resolveInstrumentKey(scripCode);
        if (resolved.isPresent()) {
            String resolvedKey = resolved.get();
            String previous = scripCodeToMStockKeyCache.put(scripCode, resolvedKey);
            if (!resolvedKey.equals(previous)) {
                if (StringUtils.hasText(previous)) {
                    mStockKeyToScripCodeCache.remove(previous);
                }
                mStockKeyToScripCodeCache.put(resolvedKey, scripCode);
            } else if (!StringUtils.hasText(previous)) {
                mStockKeyToScripCodeCache.put(resolvedKey, scripCode);
            }
            return resolvedKey;
        }

        String previous = scripCodeToMStockKeyCache.remove(scripCode);
        if (StringUtils.hasText(previous)) {
            mStockKeyToScripCodeCache.remove(previous);
        }
        return null;
    }
}
