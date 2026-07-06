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
import java.util.List;
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
    private final Map<Integer, MinuteBucket> activeMinuteCandles = new ConcurrentHashMap<>();
    private final Map<Integer, MinuteCandle> completedMinuteCandles = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<MinuteCandle>> completedMinuteCandleHistory = new ConcurrentHashMap<>();
    private final Map<Integer, RecentPriceWindow> recentPriceWindows = new ConcurrentHashMap<>();
    private static final int MAX_COMPLETED_CANDLES_PER_SCRIP = 120;

    /**
     * Update the LTP for a scripCode (called from WebSocket listener)
     */
    public void updateLtp(int scripCode, double ltp) {
        updateLtpAt(scripCode, ltp, LocalDateTime.now(IST_ZONE));
    }

    void updateLtpAt(int scripCode, double ltp, LocalDateTime observedAt) {
        ltpCache.put(scripCode, ltp);
        captureOpeningPriceIfEligible(scripCode, ltp);
        updateMinuteCandle(scripCode, ltp, observedAt);
        recordRecentPrice(scripCode, ltp, observedAt);
        log.debug("📈 Updated LTP cache: scripCode={} ltp={}", scripCode, ltp);
    }

    public MinuteCandle getLastCompletedMinuteCandle(int scripCode) {
        return completedMinuteCandles.get(scripCode);
    }

    public List<MinuteCandle> getCompletedMinuteCandlesSince(int scripCode, LocalDateTime sinceMinute) {
        Deque<MinuteCandle> history = completedMinuteCandleHistory.get(scripCode);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return history.stream()
                    .filter(candle -> sinceMinute == null || !candle.minute().isBefore(sinceMinute))
                    .toList();
        }
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
        completedMinuteCandleHistory.clear();
        recentPriceWindows.clear();
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
        activeMinuteCandles.clear();
        completedMinuteCandles.clear();
        completedMinuteCandleHistory.clear();
        recentPriceWindows.clear();
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

    private void updateMinuteCandle(int scripCode, double ltp, LocalDateTime observedAt) {
        if (observedAt == null || !Double.isFinite(ltp) || ltp <= 0d) {
            return;
        }
        LocalDateTime minute = observedAt.truncatedTo(ChronoUnit.MINUTES);
        activeMinuteCandles.compute(scripCode, (key, current) -> {
            if (current == null || !current.minute.equals(minute)) {
                if (current != null && current.minute.isBefore(minute)) {
                    recordCompletedMinuteCandle(scripCode, current.snapshot());
                }
                return new MinuteBucket(minute, ltp);
            }
            current.update(ltp);
            return current;
        });
    }

    private void recordCompletedMinuteCandle(int scripCode, MinuteCandle candle) {
        completedMinuteCandles.put(scripCode, candle);
        Deque<MinuteCandle> history = completedMinuteCandleHistory.computeIfAbsent(
                scripCode, ignored -> new ArrayDeque<>());
        synchronized (history) {
            if (!history.isEmpty() && history.peekLast().minute().equals(candle.minute())) {
                history.removeLast();
            }
            history.addLast(candle);
            while (history.size() > MAX_COMPLETED_CANDLES_PER_SCRIP) {
                history.removeFirst();
            }
        }
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
