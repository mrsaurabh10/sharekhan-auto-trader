package org.com.sharekhan.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class LtpCacheService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 15);

    // Stores latest LTP per scripCode
    private final Map<Integer, Double> ltpCache = new ConcurrentHashMap<>();
    // Stores the first LTP observed after market open for each trading day (IST)
    private final Map<LocalDate, Map<Integer, Double>> openingPriceCache = new ConcurrentHashMap<>();

    /**
     * Update the LTP for a scripCode (called from WebSocket listener)
     */
    public void updateLtp(int scripCode, double ltp) {
        ltpCache.put(scripCode, ltp);
        captureOpeningPriceIfEligible(scripCode, ltp);
        log.debug("📈 Updated LTP cache: scripCode={} ltp={}", scripCode, ltp);
    }

    /**
     * Get the latest cached LTP for a scripCode
     */
    public Double getLtp(int scripCode) {
        return ltpCache.get(scripCode);
    }

    /**
     * Check if LTP exists for the scripCode
     */
    public boolean hasLtp(int scripCode) {
        return ltpCache.containsKey(scripCode);
    }

    /**
     * Returns the captured market opening price (first LTP >= 9:15 IST) for today, if available.
     */
    public Double getTodayOpeningPrice(int scripCode) {
        LocalDate today = LocalDate.now(IST_ZONE);
        Map<Integer, Double> dailyMap = openingPriceCache.get(today);
        if (dailyMap == null) {
            return null;
        }
        return dailyMap.get(scripCode);
    }

    /**
     * Manually clear all stored opening prices (useful for tests or explicit resets).
     */
    public void clearOpeningPrices() {
        openingPriceCache.clear();
    }

    /**
     * Clear LTP for a given scripCode (optional, e.g. after trade complete)
     */
    public void removeLtp(int scripCode) {
        ltpCache.remove(scripCode);
    }

    /**
     * Clear all cached LTPs
     */
    public void clearAll() {
        ltpCache.clear();
        openingPriceCache.clear();
    }

    private void captureOpeningPriceIfEligible(int scripCode, double ltp) {
        LocalTime nowIst = LocalTime.now(IST_ZONE);
        if (nowIst.isBefore(MARKET_OPEN_TIME)) {
            return; // ignore pre-open ticks
        }

        LocalDate today = LocalDate.now(IST_ZONE);
        purgeStaleOpeningPrices(today);
        Map<Integer, Double> dailyMap = openingPriceCache.computeIfAbsent(today, d -> new ConcurrentHashMap<>());

        dailyMap.computeIfAbsent(scripCode, key -> {
            log.debug("📌 Captured opening price for scripCode={} on {}: {}", scripCode, today, ltp);
            return ltp;
        });
    }

    private void purgeStaleOpeningPrices(LocalDate today) {
        openingPriceCache.keySet().removeIf(date -> date.isBefore(today));
    }
}
