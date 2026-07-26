package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fno925EntryQualificationServiceTest {

    @Test
    void ceUsesLatestConfirmedPostRangeSwingLowInsteadOfSessionLow() {
        StrategySupport support = mock(StrategySupport.class, Answers.RETURNS_DEFAULTS);
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = new ArrayList<>();
        for (int index = 0; index < 71; index++) {
            candles.add(new StrategyCandle(day.minusDays(1), LocalTime.of(9, 15).plusMinutes(index * 5L),
                    100d, 110d, 90d, 100d, 100L));
        }
        candles.add(new StrategyCandle(day, LocalTime.of(9, 15), 100d, 105d, 95d, 102d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 20), 102d, 106d, 98d, 104d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 25), 104d, 105d, 101d, 102d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 30), 102d, 104d, 100d, 103d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 35), 103d, 108d, 102d, 107d, 250L));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));

        Fno925EntryQualificationService service = new Fno925EntryQualificationService(support);
        ReflectionTestUtils.setField(service, "orbVolumeMultiplier", 0.9d);
        ReflectionTestUtils.setField(service, "baseVolumeMultiplier", 1.15d);
        ReflectionTestUtils.setField(service, "volumeLookback", 20);
        ReflectionTestUtils.setField(service, "maxOpposingWickToRange", 0.55d);
        ReflectionTestUtils.setField(service, "minBodyToRange", 0.40d);
        ReflectionTestUtils.setField(service, "maxRiskAtrMultiplier", 1.5d);

        Fno925Candidate candidate = new Fno925Candidate("TECHM", ScriptMasterEntity.builder().build(), "CE");
        Fno925EntryQualificationService.Qualification result = service.qualify(candidate, day.atTime(9, 40));

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().entryPrice()).isEqualTo(107d);
        assertThat(result.signal().stopLoss()).isEqualTo(100d);
    }

    @Test
    void ceCanUseVwapReclaimBaseBreakWithoutBreakingOpeningRangeHigh() {
        StrategySupport support = mock(StrategySupport.class, Answers.RETURNS_DEFAULTS);
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = historicalCandles(day);
        candles.add(new StrategyCandle(day, LocalTime.of(9, 15), 100d, 105d, 95d, 100d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 20), 100d, 104d, 96d, 100d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 25), 98d, 100d, 97d, 99d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 30), 100d, 102d, 99d, 101d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 35), 101d, 103d, 100d, 102d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 40), 102d, 103d, 101d, 102.5d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 45), 102.5d, 104.5d, 102d, 104d, 250L));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));

        Fno925EntryQualificationService service = configuredService(support);
        Fno925Candidate candidate = new Fno925Candidate("TCS", ScriptMasterEntity.builder().build(), "CE");

        Fno925EntryQualificationService.Qualification result = service.qualify(candidate, day.atTime(9, 50));

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().setup()).isEqualTo("VWAP_RECLAIM_BASE_BREAKOUT");
        assertThat(result.signal().entryPrice()).isEqualTo(104d);
        assertThat(result.signal().stopLoss()).isEqualTo(99d);
    }

    @Test
    void orbVolumeUsesPostOpeningMedianInsteadOfDistortedOpeningAverage() {
        StrategySupport support = mock(StrategySupport.class, Answers.RETURNS_DEFAULTS);
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = historicalCandles(day);
        candles.add(new StrategyCandle(day, LocalTime.of(9, 15), 100d, 105d, 95d, 102d, 10_000L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 20), 102d, 106d, 98d, 104d, 10_000L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 25), 104d, 105d, 101d, 102d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 30), 102d, 104d, 100d, 103d, 100L));
        candles.add(new StrategyCandle(day, LocalTime.of(9, 35), 103d, 108d, 102d, 107d, 90L));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));

        Fno925EntryQualificationService service = configuredService(support);
        Fno925Candidate candidate = new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), "CE");

        Fno925EntryQualificationService.Qualification result = service.qualify(candidate, day.atTime(9, 40));

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().setup()).isEqualTo("MORNING_ORB");
    }

    private Fno925EntryQualificationService configuredService(StrategySupport support) {
        Fno925EntryQualificationService service = new Fno925EntryQualificationService(support);
        ReflectionTestUtils.setField(service, "orbVolumeMultiplier", 0.9d);
        ReflectionTestUtils.setField(service, "baseVolumeMultiplier", 1.15d);
        ReflectionTestUtils.setField(service, "volumeLookback", 5);
        ReflectionTestUtils.setField(service, "maxOpposingWickToRange", 0.55d);
        ReflectionTestUtils.setField(service, "minBodyToRange", 0.40d);
        ReflectionTestUtils.setField(service, "maxRiskAtrMultiplier", 1.5d);
        return service;
    }

    private List<StrategyCandle> historicalCandles(LocalDate day) {
        List<StrategyCandle> candles = new ArrayList<>();
        for (int index = 0; index < 71; index++) {
            candles.add(new StrategyCandle(day.minusDays(1), LocalTime.of(9, 15).plusMinutes(index * 5L),
                    100d, 110d, 90d, 100d, 100L));
        }
        return candles;
    }
}
