package org.com.sharekhan.cache;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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
    void retainsCompletedMinuteCandlesForConsecutiveCloseRules() {
        LtpCacheService cache = new LtpCacheService();

        cache.updateLtpAt(123, 100.0, LocalDateTime.of(2026, 7, 3, 9, 20, 1));
        cache.updateLtpAt(123, 101.0, LocalDateTime.of(2026, 7, 3, 9, 21, 1));
        cache.updateLtpAt(123, 102.0, LocalDateTime.of(2026, 7, 3, 9, 22, 1));
        cache.updateLtpAt(123, 103.0, LocalDateTime.of(2026, 7, 3, 9, 23, 1));

        assertThat(cache.getCompletedMinuteCandlesSince(123, LocalDateTime.of(2026, 7, 3, 9, 21)))
                .extracting(LtpCacheService.MinuteCandle::minute)
                .containsExactly(
                        LocalDateTime.of(2026, 7, 3, 9, 21),
                        LocalDateTime.of(2026, 7, 3, 9, 22));
    }
}
