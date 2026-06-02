package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
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

class AbstractSupertrendRsiEmaAdxStrategyTest {

    private final StrategySupport support = mock(StrategySupport.class);
    private final IndicatorService indicatorService = mock(IndicatorService.class);
    private final SupertrendRsiEmaAdxCeStrategy strategy = new SupertrendRsiEmaAdxCeStrategy(support, indicatorService);

    @Test
    void minimumCandleGateUsesRollingHistoryNotOnlyToday() {
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        LocalDate previousDay = today.minusDays(1);
        LocalTime completedTime = LocalTime.now(StrategySupport.MARKET_ZONE).minusMinutes(10);

        List<StrategyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            candles.add(candle(previousDay, LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        }
        candles.add(candle(today, completedTime, 200));

        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCandlesWithHistoricalFallback(any(), anyInt())).thenReturn(new CandleLoad(candles, false, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(support.waiting(any(), anyString(), anyString()))
                .thenReturn(StrategyApplyResponse.builder().status("waiting").build());

        strategy.apply(request("NIFTY"));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(support).waiting(any(), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("Have 49, need at least 50");
        assertThat(messageCaptor.getValue()).contains("Today's completed candles: 1");
    }

    @Test
    void indicatorSnapshotReceivesCompletedRollingCandlesWithTodayAsLatest() {
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        LocalDate previousDay = today.minusDays(1);
        LocalTime completedTime = LocalTime.now(StrategySupport.MARKET_ZONE).minusMinutes(10);

        List<StrategyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            candles.add(candle(previousDay, LocalTime.of(9, 15).plusMinutes(i * 5L), 100 + i));
        }
        candles.add(candle(today, completedTime, 200));

        when(support.resolveSpotScript("NIFTY")).thenReturn(spotScript("NIFTY"));
        when(support.loadCandlesWithHistoricalFallback(any(), anyInt())).thenReturn(new CandleLoad(candles, false, null));
        when(indicatorService.minimumCandles()).thenReturn(50);
        when(indicatorService.computeSnapshot(anyList()))
                .thenThrow(new IllegalStateException("stop-after-capture"));

        assertThatThrownBy(() -> strategy.apply(request("NIFTY")))
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
