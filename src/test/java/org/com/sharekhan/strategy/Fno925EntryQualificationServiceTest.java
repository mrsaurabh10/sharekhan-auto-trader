package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        // This fixture intentionally uses very large historical ranges to exercise the
        // VWAP-reclaim path; keep the unrelated extension/support guards out of scope.
        ReflectionTestUtils.setField(service, "baseBreakMinAtr", 0.04d);
        ReflectionTestUtils.setField(service, "sessionExtremeBufferAtr", 0d);
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

    @Test
    void ceAllowsCloseWithinConfiguredVwapSideTolerance() {
        Fno925EntryQualificationService service = configuredService(mock(StrategySupport.class));
        ReflectionTestUtils.setField(service, "minBodyToRange", 0.30d);
        StrategyCandle reference = candle(LocalTime.of(9, 15), 100d, 100d, 100d, 100d);
        StrategyCandle breakout = candle(LocalTime.of(9, 20), 99.8d, 100.1d, 99.7d, 99.95d);
        List<StrategyCandle> candles = List.of(reference, breakout);

        Optional<String> accepted = ReflectionTestUtils.invokeMethod(service, "filterFailure", candles, breakout,
                "CE", List.of(reference), 0.75d);
        assertThat(accepted).isEmpty();

        ReflectionTestUtils.setField(service, "vwapSideTolerance", 0d);
        Optional<String> rejected = ReflectionTestUtils.invokeMethod(service, "filterFailure", candles, breakout,
                "CE", List.of(reference), 0.75d);
        assertThat(rejected).contains("breakout close is on the wrong side of VWAP");
    }

    @Test
    void reclaimRequiresOnlyLatestBaseCandleWhenConfiguredForOneConfirmation() {
        Fno925EntryQualificationService service = configuredService(mock(StrategySupport.class));
        StrategyCandle firstBase = candle(LocalTime.of(9, 15), 100d, 100d, 100d, 100d);
        StrategyCandle secondBase = candle(LocalTime.of(9, 20), 100d, 101d, 99d, 99d);
        StrategyCandle latestBase = candle(LocalTime.of(9, 25), 100d, 103d, 100d, 102.5d);
        StrategyCandle breakout = candle(LocalTime.of(9, 30), 102.5d, 104d, 101d, 103d);
        List<StrategyCandle> candles = List.of(firstBase, secondBase, latestBase, breakout);
        List<StrategyCandle> base = List.of(firstBase, secondBase, latestBase);

        Boolean oneConfirmation = ReflectionTestUtils.invokeMethod(service, "holdsReclaimedVwap", candles, base,
                breakout, "CE");
        assertThat(oneConfirmation).isTrue();

        ReflectionTestUtils.setField(service, "reclaimVwapConfirmations", 2);
        Boolean twoConfirmations = ReflectionTestUtils.invokeMethod(service, "holdsReclaimedVwap", candles, base,
                breakout, "CE");
        assertThat(twoConfirmations).isFalse();
    }

    @Test
    void vwapBaseBreakRequiresARecentVwapRetestAndRejectsExtendedSupportEntries() {
        Fno925EntryQualificationService service = configuredService(mock(StrategySupport.class));
        List<StrategyCandle> candles = List.of(
                candle(LocalTime.of(9, 15), 100d, 100d, 100d, 100d),
                candle(LocalTime.of(9, 20), 90d, 90d, 89d, 89.5d),
                candle(LocalTime.of(9, 25), 89.5d, 90d, 89d, 89.4d),
                candle(LocalTime.of(9, 30), 89.4d, 90d, 89d, 89.3d));
        List<StrategyCandle> base = candles.subList(1, 4);

        Boolean hasRetest = ReflectionTestUtils.invokeMethod(service, "hasRecentVwapRetest", candles, base, "PE", 2d);
        Boolean tooExtended = ReflectionTestUtils.invokeMethod(service, "isTooExtendedFromVwap", candles,
                candle(LocalTime.of(9, 35), 89d, 89d, 84d, 84d), "PE", 2d);
        Boolean nearSupport = ReflectionTestUtils.invokeMethod(service, "isNearPriorSessionExtreme", base,
                candle(LocalTime.of(9, 35), 89.4d, 89.5d, 89.2d, 89.5d), "PE", 2d);

        assertThat(hasRetest).isFalse();
        assertThat(tooExtended).isTrue();
        assertThat(nearSupport).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"CE,MORNING_ORB", "PE,MORNING_ORB", "CE,VWAP_RECLAIM_BASE_BREAKOUT", "PE,VWAP_RECLAIM_BASE_BREAKOUT"})
    void moverAcceptsMatchingDirectionForBothEntrySetups(String optionType, String setup) {
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = directionalFixture(day, optionType, setup, false);
        Fno925EntryQualificationService service = directionalService(candles);
        LocalDateTime now = day.atTime(setup.equals("MORNING_ORB") ? LocalTime.of(9, 40) : LocalTime.of(9, 50));

        var result = service.qualifyWithDirectionalCandle(new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), optionType), now);

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().setup()).isEqualTo(setup);
    }

    @ParameterizedTest
    @CsvSource({"CE,MORNING_ORB", "PE,MORNING_ORB", "CE,VWAP_RECLAIM_BASE_BREAKOUT", "PE,VWAP_RECLAIM_BASE_BREAKOUT"})
    void moverRejectsOppositeDirectionWithoutChangingLegacyQualification(String optionType, String setup) {
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = directionalFixture(day, optionType, setup, true);
        Fno925EntryQualificationService service = directionalService(candles);
        LocalDateTime now = day.atTime(setup.equals("MORNING_ORB") ? LocalTime.of(9, 40) : LocalTime.of(9, 50));
        Fno925Candidate candidate = new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), optionType);

        assertThat(service.qualify(candidate, now).qualified()).isTrue();
        var rejected = service.qualifyWithDirectionalCandle(candidate, now);

        assertThat(rejected.qualified()).isFalse();
        assertThat(rejected.reason()).contains("entry requires a "
                + (optionType.equals("CE") ? "bullish" : "bearish") + " completed five-minute signal candle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CE", "PE"})
    void moverRejectsFlatCompletedSignalCandle(String optionType) {
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = directionalFixture(day, optionType, "MORNING_ORB", false);
        StrategyCandle last = candles.get(candles.size() - 1);
        candles.set(candles.size() - 1, new StrategyCandle(day, last.time(), last.close(), last.high(), last.low(), last.close(), 250L));

        var result = directionalService(candles).qualifyWithDirectionalCandle(
                new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), optionType), day.atTime(9, 40));

        assertThat(result.qualified()).isFalse();
        assertThat(result.reason()).contains("completed five-minute signal candle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CE", "PE"})
    void moverUsesCompletedSignalDirectionAndIgnoresOppositeFormingCandle(String optionType) {
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = directionalFixture(day, optionType, "MORNING_ORB", false);
        StrategyCandle forming = new StrategyCandle(day, LocalTime.of(9, 40), 109d, 110d, 106d, 107d, 250L);
        candles.add(optionType.equals("CE") ? forming : mirror(forming));

        var result = directionalService(candles).qualifyWithDirectionalCandle(
                new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), optionType), day.atTime(9, 42));

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().breakoutCandle().time()).isEqualTo(LocalTime.of(9, 35));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CE", "PE"})
    void laterMatchingCandleCanQualifyAfterAnOppositeDirectionWasRejected(String optionType) {
        LocalDate day = LocalDate.of(2026, 7, 17);
        List<StrategyCandle> candles = directionalFixture(day, optionType, "MORNING_ORB", true);
        Fno925EntryQualificationService service = directionalService(candles);
        Fno925Candidate candidate = new Fno925Candidate("TEST", ScriptMasterEntity.builder().build(), optionType);

        assertThat(service.qualifyWithDirectionalCandle(candidate, day.atTime(9, 40)).qualified()).isFalse();
        StrategyCandle next = new StrategyCandle(day, LocalTime.of(9, 40), 107d, 109d, 106d, 108.5d, 250L);
        candles.add(optionType.equals("CE") ? next : mirror(next));

        var result = service.qualifyWithDirectionalCandle(candidate, day.atTime(9, 45));
        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().breakoutCandle().time()).isEqualTo(LocalTime.of(9, 40));
    }

    private Fno925EntryQualificationService directionalService(List<StrategyCandle> candles) {
        StrategySupport support = mock(StrategySupport.class);
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));
        Fno925EntryQualificationService service = configuredService(support);
        // Match the existing reclaim fixture; isolate direction from unrelated extension/support checks.
        ReflectionTestUtils.setField(service, "baseBreakMinAtr", 0.04d);
        ReflectionTestUtils.setField(service, "sessionExtremeBufferAtr", 0d);
        return service;
    }

    private List<StrategyCandle> directionalFixture(LocalDate day, String optionType, String setup, boolean opposite) {
        List<StrategyCandle> candles = historicalCandles(day);
        if (setup.equals("MORNING_ORB")) {
            candles.add(candle(LocalTime.of(9, 15), 100d, 105d, 95d, 102d));
            candles.add(candle(LocalTime.of(9, 20), 102d, 106d, 98d, 104d));
            candles.add(candle(LocalTime.of(9, 25), 104d, 105d, 101d, 102d));
            candles.add(candle(LocalTime.of(9, 30), 102d, 104d, 100d, 103d));
            candles.add(opposite ? new StrategyCandle(day, LocalTime.of(9, 35), 109d, 109.5d, 106.5d, 107d, 250L)
                    : new StrategyCandle(day, LocalTime.of(9, 35), 103d, 108d, 102d, 107d, 250L));
        } else {
            candles.add(candle(LocalTime.of(9, 15), 100d, 105d, 95d, 100d));
            candles.add(candle(LocalTime.of(9, 20), 100d, 104d, 96d, 100d));
            candles.add(candle(LocalTime.of(9, 25), 98d, 100d, 97d, 99d));
            candles.add(candle(LocalTime.of(9, 30), 100d, 102d, 99d, 101d));
            candles.add(candle(LocalTime.of(9, 35), 101d, 103d, 100d, 102d));
            candles.add(candle(LocalTime.of(9, 40), 102d, 103d, 101d, 102.5d));
            candles.add(opposite ? new StrategyCandle(day, LocalTime.of(9, 45), 105d, 105.2d, 103.9d, 104d, 250L)
                    : new StrategyCandle(day, LocalTime.of(9, 45), 102.5d, 104.5d, 102d, 104d, 250L));
        }
        return optionType.equals("CE") ? candles : new ArrayList<>(candles.stream().map(this::mirror).toList());
    }

    private StrategyCandle mirror(StrategyCandle candle) {
        return new StrategyCandle(candle.date(), candle.time(), 200d - candle.open(), 200d - candle.low(),
                200d - candle.high(), 200d - candle.close(), candle.volume());
    }

    private Fno925EntryQualificationService configuredService(StrategySupport support) {
        Fno925EntryQualificationService service = new Fno925EntryQualificationService(support);
        ReflectionTestUtils.setField(service, "orbVolumeMultiplier", 0.9d);
        ReflectionTestUtils.setField(service, "baseVolumeMultiplier", 1.15d);
        ReflectionTestUtils.setField(service, "volumeLookback", 5);
        ReflectionTestUtils.setField(service, "maxOpposingWickToRange", 0.55d);
        ReflectionTestUtils.setField(service, "minBodyToRange", 0.40d);
        ReflectionTestUtils.setField(service, "maxRiskAtrMultiplier", 1.5d);
        ReflectionTestUtils.setField(service, "reclaimVwapConfirmations", 1);
        ReflectionTestUtils.setField(service, "vwapSideTolerance", 0.001d);
        ReflectionTestUtils.setField(service, "vwapDirectionTolerance", 0.0003d);
        return service;
    }

    private StrategyCandle candle(LocalTime time, double open, double high, double low, double close) {
        return new StrategyCandle(LocalDate.of(2026, 7, 17), time, open, high, low, close, 100L);
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
