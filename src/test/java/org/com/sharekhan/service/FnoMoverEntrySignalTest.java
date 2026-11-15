package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class FnoMoverEntrySignalTest {
    final LocalDateTime candle=LocalDateTime.of(2026,11,13,9,55);
    @Test void deadlineIsNextCompletedCandleBoundaryAndSurvivesStoredStringRoundTrip() {
        var text=FnoMoverEntrySignal.decision(candle);
        assertThat(FnoMoverEntrySignal.valid(text,candle.plusMinutes(5).minusNanos(1))).isFalse();
        assertThat(FnoMoverEntrySignal.valid(new String(text),candle.plusMinutes(5))).isTrue();
        assertThat(FnoMoverEntrySignal.valid(text,candle.plusMinutes(10).minusNanos(1))).isTrue();
        assertThat(FnoMoverEntrySignal.valid(text,candle.plusMinutes(10))).isFalse();
        assertThat(FnoMoverEntrySignal.valid(text,candle.plusDays(1))).isFalse();
    }
    @Test void missingMalformedOrExtendedDeadlinesFailClosed() {
        var text=FnoMoverEntrySignal.decision(candle);
        for(String invalid:new String[]{"", "legacy comment",text+";FNO_ENTRY_DEADLINE=2026-11-13T11:00",text.replace("10:05","11:05"),text.replace("09:55","nonsense")})
            assertThat(FnoMoverEntrySignal.valid(invalid,candle.plusMinutes(6))).as(invalid).isFalse();
        assertThat(FnoMoverEntrySignal.valid(null,candle.plusMinutes(6))).isFalse();
        assertThat(FnoMoverEntrySignal.valid(text,null)).isFalse();
    }
    @Test void onlyAlignedRegularSessionSignalsCanBeStamped() {
        for(var invalid:new LocalDateTime[]{candle.withMinute(56),candle.withSecond(1),candle.withHour(8),candle.withHour(15).withMinute(25)})
            assertThatThrownBy(()->FnoMoverEntrySignal.decision(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
