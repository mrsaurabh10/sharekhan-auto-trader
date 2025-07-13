package org.com.sharekhan.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class LtpCacheService {

    // Stores latest LTP per scripCode
    private final Map<Integer, Double> ltpCache = new ConcurrentHashMap<>();

    /**
     * Update the LTP for a scripCode (called from WebSocket listener)
     */
    public void updateLtp(int scripCode, double ltp) {
        ltpCache.put(scripCode, ltp);
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
    }
}