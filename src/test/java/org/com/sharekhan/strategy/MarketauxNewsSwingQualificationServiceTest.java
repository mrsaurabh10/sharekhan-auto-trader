package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketauxNewsSwingQualificationServiceTest {

    private final StrategySupport support = mock(StrategySupport.class);
    private final MarketauxNewsSwingQualificationService service = new MarketauxNewsSwingQualificationService(support);

    @Test
    void qualifiesOnlyWhenPostNewsSwingBreakClosesWithRelativeVolume() {
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        List<StrategyCandle> candles = new ArrayList<>();
        for (int index = 0; index < 76; index++) {
            candles.add(candle(today.minusDays(1), LocalTime.of(9, 15), 100d, 101d, 99d, 100d, 100L));
        }
        candles.add(candle(today, LocalTime.of(9, 20), 100d, 102d, 99d, 101d, 100L));
        candles.add(candle(today, LocalTime.of(9, 25), 101d, 104d, 100d, 103d, 100L));
        candles.add(candle(today, LocalTime.of(9, 30), 103d, 110d, 102d, 104d, 100L));
        candles.add(candle(today, LocalTime.of(9, 35), 104d, 106d, 101d, 103d, 100L));
        candles.add(candle(today, LocalTime.of(9, 40), 103d, 105d, 100d, 104d, 100L));
        candles.add(candle(today, LocalTime.of(9, 45), 104d, 112d, 103d, 111d, 160L));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));

        Fno925EntryQualificationService.Qualification result = service.qualify(
                ScriptMasterEntity.builder().tradingSymbol("TEST").build(), "CE",
                LocalDateTime.of(today, LocalTime.of(9, 20)), LocalDateTime.of(today, LocalTime.of(9, 50)));

        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().entryPrice()).isEqualTo(111d);
        assertThat(result.signal().setup()).isEqualTo("POST_NEWS_SWING_HIGH_CLOSE_BREAKOUT_VOLUME");
    }

    @Test
    void rejectsACloseBreakWithoutRelativeVolume() {
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        List<StrategyCandle> candles = new ArrayList<>();
        for (int index = 0; index < 76; index++) candles.add(candle(today.minusDays(1), LocalTime.of(9, 15), 100d, 101d, 99d, 100d, 100L));
        candles.add(candle(today, LocalTime.of(9, 20), 100d, 102d, 99d, 101d, 100L));
        candles.add(candle(today, LocalTime.of(9, 25), 101d, 104d, 100d, 103d, 100L));
        candles.add(candle(today, LocalTime.of(9, 30), 103d, 110d, 102d, 104d, 100L));
        candles.add(candle(today, LocalTime.of(9, 35), 104d, 106d, 101d, 103d, 100L));
        candles.add(candle(today, LocalTime.of(9, 40), 103d, 105d, 100d, 104d, 100L));
        candles.add(candle(today, LocalTime.of(9, 45), 104d, 112d, 103d, 111d, 120L));
        when(support.loadCandlesWithHistoricalFallback(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(76)))
                .thenReturn(new CandleLoad(candles, true, null));
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));

        Fno925EntryQualificationService.Qualification result = service.qualify(
                ScriptMasterEntity.builder().tradingSymbol("TEST").build(), "CE",
                LocalDateTime.of(today, LocalTime.of(9, 20)), LocalDateTime.of(today, LocalTime.of(9, 50)));

        assertThat(result.qualified()).isFalse();
        assertThat(result.reason()).contains("volume");
    }

    private StrategyCandle candle(LocalDate date, LocalTime time, double open, double high, double low, double close, long volume) {
        return new StrategyCandle(date, time, open, high, low, close, volume);
    }
}
