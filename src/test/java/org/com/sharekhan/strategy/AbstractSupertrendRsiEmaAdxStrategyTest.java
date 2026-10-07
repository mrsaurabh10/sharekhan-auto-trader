package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AbstractSupertrendRsiEmaAdxStrategyTest {

    private final StrategySupport support = mock(StrategySupport.class);
    private final IndicatorService indicatorService = mock(IndicatorService.class);
    private final SupertrendRsiEmaAdxCeStrategy strategy = new SupertrendRsiEmaAdxCeStrategy(support, indicatorService,
            new SupertrendSignalRules(new org.com.sharekhan.config.SupertrendStrategyProperties()),
            mock(SupertrendDecisionDiagnostics.class));

    @ParameterizedTest
    @CsvSource({"CE,1,false", "CE,2,true", "CE,3,true", "PE,1,false", "PE,2,true", "PE,3,true"})
    void enablesTslForMultiLotCeAndPeRequests(String direction, int lots, boolean expectedTsl) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 9, 30);
        List<StrategyCandle> candles = rollingCandles(now);
        boolean pe = "PE".equals(direction);
        StrategyCandle signal = new StrategyCandle(now.toLocalDate(), LocalTime.of(9, 25),
                pe ? 201 : 199, 202, 198, 200, 1000L);
        candles.set(49, signal);
        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCompletedIndicatorCandles(any(), anyInt(), any()))
                .thenReturn(new CandleLoad(candles, true, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(indicatorService.computeSnapshot(anyList())).thenReturn(new IndicatorSnapshot(
                signal, pe ? 210 : 190, pe ? 40 : 60, pe ? 41 : 59, pe ? 205 : 195,
                25, pe ? 15 : 30, pe ? 30 : 15));
        when(support.roundPrice(org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(call -> call.getArgument(0));
        AbstractSupertrendRsiEmaAdxStrategy template = pe
                ? new SupertrendRsiEmaAdxPeStrategy(support, indicatorService,
                    new SupertrendSignalRules(new org.com.sharekhan.config.SupertrendStrategyProperties()),
                    mock(SupertrendDecisionDiagnostics.class)) : strategy;
        StrategyApplyRequest request = request("NIFTY");
        request.setLots(lots);

        assertThat(template.apply(request, now).getStatus()).isEqualTo("triggered");

        ArgumentCaptor<org.com.sharekhan.dto.TriggerRequest> trigger =
                ArgumentCaptor.forClass(org.com.sharekhan.dto.TriggerRequest.class);
        verify(support).executeTriggeredTrade(trigger.capture());
        assertThat(trigger.getValue().getTslEnabled()).isEqualTo(expectedTsl);
        assertThat(trigger.getValue().getLots()).isEqualTo(lots);
        assertThat(trigger.getValue().getUseSpotForSl()).isTrue();
        assertThat(trigger.getValue().getUseSpotForTarget()).isTrue();
    }

    @Test
    void eligibleMorningSignalExecutesWithPreviousSessionHistory() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 9, 30);
        List<StrategyCandle> candles = rollingCandles(now);
        StrategyCandle latest = candles.get(49);
        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCompletedIndicatorCandles(any(), anyInt(), any())).thenReturn(new CandleLoad(candles, true, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(indicatorService.computeSnapshot(anyList())).thenReturn(
                new IndicatorSnapshot(latest, 190, 60, 59, 195, 25, 30, 15));
        when(support.roundPrice(org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(call -> call.getArgument(0));

        assertThat(strategy.apply(request("NIFTY"), now).getStatus()).isEqualTo("triggered");
        verify(support).executeTriggeredTrade(any());
    }

    @Test
    void staleCachedIntradaySignalCannotTriggerAnEntry() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 9, 35);
        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCompletedIndicatorCandles(any(), anyInt(), any()))
                .thenReturn(new CandleLoad(rollingCandles(now), true, "feed unavailable"));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(support.waiting(any(), anyString(), anyString())).thenReturn(StrategyApplyResponse.builder().status("waiting").build());

        assertThat(strategy.apply(request("NIFTY"), now).getStatus()).isEqualTo("waiting");
        verify(indicatorService, never()).computeSnapshot(anyList());
        verify(support, never()).executeTriggeredTrade(any());
    }

    private List<StrategyCandle> rollingCandles(LocalDateTime now) {
        List<StrategyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 49; i++) candles.add(candle(now.toLocalDate().minusDays(3), LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        candles.add(candle(now.toLocalDate(), LocalTime.of(9, 25), 200));
        return candles;
    }

    @Test
    void minimumCandleGateUsesRollingHistoryNotOnlyToday() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 9, 30);
        LocalDate today = now.toLocalDate();
        LocalDate previousDay = today.minusDays(1);
        LocalTime completedTime = LocalTime.of(9, 25);

        List<StrategyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            candles.add(candle(previousDay, LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        }
        candles.add(candle(today, completedTime, 200));

        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCompletedIndicatorCandles(any(), anyInt(), any())).thenReturn(new CandleLoad(candles, false, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(support.waiting(any(), anyString(), anyString()))
                .thenReturn(StrategyApplyResponse.builder().status("waiting").build());

        strategy.apply(request("NIFTY"), now);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(support).waiting(any(), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("Have 49, need at least 50");
        assertThat(messageCaptor.getValue()).contains("Today's completed candles: 1");
    }

    @Test
    void indicatorSnapshotReceivesCompletedRollingCandlesWithTodayAsLatest() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 9, 30);
        LocalDate today = now.toLocalDate();
        LocalDate previousDay = today.minusDays(1);
        LocalTime completedTime = LocalTime.of(9, 25);

        List<StrategyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            candles.add(candle(previousDay, LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        }
        candles.add(candle(today, completedTime, 200));

        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCompletedIndicatorCandles(any(), anyInt(), any())).thenReturn(new CandleLoad(candles, false, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(indicatorService.computeSnapshot(anyList()))
                .thenThrow(new IllegalStateException("stop-after-capture"));

        assertThatThrownBy(() -> strategy.apply(request("NIFTY"), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stop-after-capture");

        ArgumentCaptor<List<StrategyCandle>> candlesCaptor = ArgumentCaptor.forClass(List.class);
        verify(indicatorService).computeSnapshot(candlesCaptor.capture());
        List<StrategyCandle> usedCandles = candlesCaptor.getValue();
        assertThat(usedCandles).hasSize(50);
        assertThat(usedCandles.get(0).date()).isEqualTo(previousDay);
        assertThat(usedCandles.get(48).date()).isEqualTo(previousDay);
        assertThat(usedCandles.get(49).date()).isEqualTo(today);
    }

    private StrategyApplyRequest request(String symbol) {
        StrategyApplyRequest request = new StrategyApplyRequest();
        request.setSymbol(symbol);
        return request;
    }

    private ScriptMasterEntity spotScript(String symbol) {
        return ScriptMasterEntity.builder()
                .scripCode(20000)
                .tradingSymbol(symbol)
                .exchange("NC")
                .instrumentType("EQ")
                .build();
    }

    private StrategyCandle candle(LocalDate date, LocalTime time, double close) {
        return new StrategyCandle(date, time, close - 1, close + 1, close - 2, close, 1_000L);
    }
}
