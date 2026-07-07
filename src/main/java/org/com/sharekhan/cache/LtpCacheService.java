package org.com.sharekhan.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class LtpCacheService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 15);
    private static final LocalTime MARKET_OPEN_CAPTURE_END = MARKET_OPEN_TIME.plusMinutes(1);

    // Stores latest LTP per scripCode
    private final Map<Integer, Double> ltpCache = new ConcurrentHashMap<>();
    // Timestamp of the latest tick, used by read-only monitoring consumers to reject stale prices.
    private final Map<Integer, LocalDateTime> ltpObservedAtCache = new ConcurrentHashMap<>();
    // Stores the first LTP observed after market open for each trading day (IST)
    private final Map<LocalDate, Map<Integer, Double>> openingPriceCache = new ConcurrentHashMap<>();
    private final Map<Integer, MinuteBucket> activeMinuteCandles = new ConcurrentHashMap<>();
    private final Map<Integer, MinuteCandle> completedMinuteCandles = new ConcurrentHashMap<>();
    private final Map<Integer, RecentPriceWindow> recentPriceWindows = new ConcurrentHashMap<>();

    /**
     * Update the LTP for a scripCode (called from WebSocket listener)
     */
    public void updateLtp(int scripCode, double ltp) {
        updateLtpAt(scripCode, ltp, LocalDateTime.now(IST_ZONE));
    }

    void updateLtpAt(int scripCode, double ltp, LocalDateTime observedAt) {
        ltpCache.put(scripCode, ltp);
        if (observedAt != null) {
            ltpObservedAtCache.put(scripCode, observedAt);
        }
        captureOpeningPriceIfEligible(scripCode, ltp, observedAt);
        updateMinuteCandle(scripCode, ltp, observedAt);
        recordRecentPrice(scripCode, ltp, observedAt);
        log.debug("📈 Updated LTP cache: scripCode={} ltp={}", scripCode, ltp);
    }

    public MinuteCandle getLastCompletedMinuteCandle(int scripCode) {
        return completedMinuteCandles.get(scripCode);
    }

    /**
     * Checks ticks observed after a request was created. This preserves target touches from the
     * request's partial first minute even when the per-scrip latest-only executor drops a callback.
     */
    public boolean hasPriceTouchedSince(int scripCode,
                                        LocalDateTime since,
                                        double target,
                                        boolean atOrBelow) {
        if (since == null || !Double.isFinite(target)) {
            return false;
        }
        RecentPriceWindow window = recentPriceWindows.get(scripCode);
        return window != null && window.hasTouchedSince(since, target, atOrBelow);
    }

    /**
     * Get the latest cached LTP for a scripCode
     */
    public Double getLtp(int scripCode) {
        return ltpCache.get(scripCode);
    }

    public LocalDateTime getObservedAt(int scripCode) {
        return ltpObservedAtCache.get(scripCode);
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
        activeMinuteCandles.clear();
        completedMinuteCandles.clear();
        recentPriceWindows.clear();
    }

    /**
     * Clear LTP for a given scripCode (optional, e.g. after trade complete)
     */
    public void removeLtp(int scripCode) {
        ltpCache.remove(scripCode);
        ltpObservedAtCache.remove(scripCode);
    }

    /**
     * Clear all cached LTPs
     */
    public void clearAll() {
        ltpCache.clear();
        ltpObservedAtCache.clear();
        openingPriceCache.clear();
        activeMinuteCandles.clear();
        completedMinuteCandles.clear();
        recentPriceWindows.clear();
    }

    private void captureOpeningPriceIfEligible(int scripCode, double ltp, LocalDateTime observedAt) {
        if (observedAt == null || !Double.isFinite(ltp) || ltp <= 0d) {
            return;
        }
        LocalTime observedTime = observedAt.toLocalTime();
        if (observedTime.isBefore(MARKET_OPEN_TIME)
                || !observedTime.isBefore(MARKET_OPEN_CAPTURE_END)) {
            return; // only a tick from the actual 09:15 opening minute can represent the open
        }

        LocalDate observedDate = observedAt.toLocalDate();
        purgeStaleOpeningPrices(observedDate);
        Map<Integer, Double> dailyMap = openingPriceCache.computeIfAbsent(observedDate, d -> new ConcurrentHashMap<>());

        dailyMap.computeIfAbsent(scripCode, key -> {
            log.debug("📌 Captured opening price for scripCode={} on {}: {}", scripCode, observedDate, ltp);
            return ltp;
        });
    }

    private void purgeStaleOpeningPrices(LocalDate today) {
        openingPriceCache.keySet().removeIf(date -> date.isBefore(today));
    }

    private void updateMinuteCandle(int scripCode, double ltp, LocalDateTime observedAt) {
        if (observedAt == null || !Double.isFinite(ltp) || ltp <= 0d) {
            return;
        }
        LocalDateTime minute = observedAt.truncatedTo(ChronoUnit.MINUTES);
        activeMinuteCandles.compute(scripCode, (key, current) -> {
            if (current == null || !current.minute.equals(minute)) {
                if (current != null && current.minute.isBefore(minute)) {
                    completedMinuteCandles.put(scripCode, current.snapshot());
                }
                return new MinuteBucket(minute, ltp);
            }
            current.update(ltp);
            return current;
        });
    }

    private void recordRecentPrice(int scripCode, double ltp, LocalDateTime observedAt) {
        if (observedAt == null || !Double.isFinite(ltp) || ltp <= 0d) {
            return;
        }
        recentPriceWindows.computeIfAbsent(scripCode, ignored -> new RecentPriceWindow())
                .add(observedAt, ltp);
    }

    public record MinuteCandle(LocalDateTime minute,
                               double open,
                               double high,
                               double low,
                               double close) {
    }

    private static final class MinuteBucket {
        private final LocalDateTime minute;
        private final double open;
        private double high;
        private double low;
        private double close;

        private MinuteBucket(LocalDateTime minute, double price) {
            this.minute = minute;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
        }

        private void update(double price) {
            high = Math.max(high, price);
            low = Math.min(low, price);
            close = price;
        }

        private MinuteCandle snapshot() {
            return new MinuteCandle(minute, open, high, low, close);
        }
    }

    private static final class RecentPriceWindow {
        private static final long RETENTION_MINUTES = 2;
        private static final int MAX_OBSERVATIONS = 20_000;
        private final Deque<PriceObservation> observations = new ArrayDeque<>();

        private synchronized void add(LocalDateTime observedAt, double price) {
            observations.addLast(new PriceObservation(observedAt, price));
            LocalDateTime cutoff = observedAt.minusMinutes(RETENTION_MINUTES);
            while (!observations.isEmpty()
                    && (observations.peekFirst().observedAt().isBefore(cutoff)
                        || observations.size() > MAX_OBSERVATIONS)) {
                observations.removeFirst();
            }
        }

        private synchronized boolean hasTouchedSince(LocalDateTime since,
                                                     double target,
                                                     boolean atOrBelow) {
            return observations.stream()
                    .filter(observation -> !observation.observedAt().isBefore(since))
                    .anyMatch(observation -> atOrBelow
                            ? observation.price() <= target
                            : observation.price() >= target);
        }
    }

    private record PriceObservation(LocalDateTime observedAt, double price) {
    }
}
