package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.UserConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtrPreviousDayBreakoutQualificationServiceTest {

    @Test
    void usesMStockHistoricalCandlesWhenWeekendIntradayChartIsEmpty() {
        StrategySupport support = mock(StrategySupport.class);
        MStockHistoricalService historical = mock(MStockHistoricalService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        AtrPreviousDayBreakoutQualificationService service =
                new AtrPreviousDayBreakoutQualificationService(support, historical, userConfigService);
        ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("PFC").scripCode(14299).build();
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDateTime sunday = LocalDateTime.of(2026, 8, 9, 18, 34);

        when(support.loadCandles(spot, "5minute")).thenReturn(new CandleLoad(List.of(), false, "market closed"));
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> {
            double value = invocation.getArgument(0);
            return Math.round(value * 100d) / 100d;
        });
        when(historical.getHistoricalCandles(eq(14299), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("5minute"), any(String.class), eq("2026-08-09")))
                .thenReturn(MStockHistoricalService.HistoricalResponse.builder().candles(fridayCandles(friday)).build());

        Fno925EntryQualificationService.Qualification qualification = service.qualify(spot, "CE", 1L, sunday);

        assertThat(qualification.qualified()).isTrue();
        assertThat(qualification.signal().entryPrice()).isGreaterThan(100d);
        assertThat(qualification.signal().stopLoss()).isLessThan(qualification.signal().entryPrice());
    }

    private List<MStockHistoricalService.HistoricalCandle> fridayCandles(LocalDate friday) {
        return java.util.stream.IntStream.range(0, 76)
                .mapToObj(index -> {
                    double high = index == 40 ? 102d : 100.5d;
                    return MStockHistoricalService.HistoricalCandle.builder()
                            .date(friday).time(LocalTime.of(9, 15).plusMinutes(index * 5L))
                            .open(100d).high(high).low(99.5d).close(100d).volume(1_000L).build();
                })
                .toList();
    }
}
