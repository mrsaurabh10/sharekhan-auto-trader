package org.com.sharekhan.cache;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LtpCacheServiceTest {

    @Test
    void publishesCompletedOneMinuteOhlcWhenNextMinuteStarts() {
        LtpCacheService cache = new LtpCacheService();

        cache.updateLtpAt(123, 100.0, LocalDateTime.of(2026, 7, 3, 9, 20, 1));
        cache.updateLtpAt(123, 103.0, LocalDateTime.of(2026, 7, 3, 9, 20, 20));
        cache.updateLtpAt(123, 98.0, LocalDateTime.of(2026, 7, 3, 9, 20, 40));
        cache.updateLtpAt(123, 101.0, LocalDateTime.of(2026, 7, 3, 9, 20, 59));

        assertThat(cache.getLastCompletedMinuteCandle(123)).isNull();

        cache.updateLtpAt(123, 102.0, LocalDateTime.of(2026, 7, 3, 9, 21, 0));

        assertThat(cache.getLastCompletedMinuteCandle(123))
                .isEqualTo(new LtpCacheService.MinuteCandle(
                        LocalDateTime.of(2026, 7, 3, 9, 20),
                        100.0, 103.0, 98.0, 101.0));
    }

    @Test
    void detectsTargetTouchOnlyFromTicksObservedAfterRequestCreation() {
        LtpCacheService cache = new LtpCacheService();
        LocalDateTime requestCreatedAt = LocalDateTime.of(2026, 7, 3, 9, 20, 10);

        cache.updateLtpAt(123, 111.0, LocalDateTime.of(2026, 7, 3, 9, 20, 5));
        cache.updateLtpAt(123, 109.0, LocalDateTime.of(2026, 7, 3, 9, 20, 20));

        assertThat(cache.hasPriceTouchedSince(123, requestCreatedAt, 110.0, false)).isFalse();

        cache.updateLtpAt(123, 110.0, LocalDateTime.of(2026, 7, 3, 9, 20, 30));

        assertThat(cache.hasPriceTouchedSince(123, requestCreatedAt, 110.0, false)).isTrue();
    }

    @Test
    void doesNotTreatFirstLateSubscriptionTickAsMarketOpen() {
        LtpCacheService cache = new LtpCacheService();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        cache.updateLtpAt(123, 152.7, today.atTime(10, 19, 8));

        assertThat(cache.getTodayOpeningPrice(123)).isNull();
    }

    @Test
    void capturesFirstTickFromActualOpeningMinute() {
        LtpCacheService cache = new LtpCacheService();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        cache.updateLtpAt(123, 140.0, today.atTime(9, 15, 2));
        cache.updateLtpAt(123, 141.0, today.atTime(9, 15, 5));

        assertThat(cache.getTodayOpeningPrice(123)).isEqualTo(140.0);
    }

    @Test
    void keepsSharekhanWebsocketTickSeparateFromLaterPollingUpdate() {
        LtpCacheService cache = new LtpCacheService();
        cache.updateSharekhanWebSocketLtp(123, 40.50);
        cache.updateLtp(123, 29.60); // e.g. a backup quote-provider poll

        assertThat(cache.getLtp(123)).isEqualTo(29.60);
        assertThat(cache.getFreshSharekhanWebSocketLtp(123, Duration.ofSeconds(1))).isEqualTo(40.50);
    }

}
