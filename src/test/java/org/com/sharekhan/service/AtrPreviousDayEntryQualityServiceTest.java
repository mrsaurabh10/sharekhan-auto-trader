package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtrPreviousDayEntryQualityServiceTest {
    private final MStockIntradayCandleService candles = mock(MStockIntradayCandleService.class);
    private final AtrPreviousDayEntryQualityService service = new AtrPreviousDayEntryQualityService(candles);

    @Test
    void acceptsConfirmedCeWithAlignedTrendAndModestVwapExtension() {
        configureStrictMode();
        List<MStockIntradayCandleService.IntradayCandle> bars = risingBars();
        when(candles.getCompletedFiveMinuteCandles(eq(4717), any())).thenReturn(bars);

        var result = service.evaluate(ceTrigger(), LocalDateTime.of(2026, 8, 19, 13, 10), 173.65d);

        assertThat(result.ready()).isTrue();
    }

    @Test
    void rejectsSingleCandleBreakoutEvenWhenCurrentSpotIsBeyondEntry() {
        configureStrictMode();
        List<MStockIntradayCandleService.IntradayCandle> bars = risingBars();
        var previous = bars.get(bars.size() - 2);
        bars.set(bars.size() - 2, new MStockIntradayCandleService.IntradayCandle(
                previous.date(), previous.time(), previous.open(), previous.high(), previous.low(), 173.4d, previous.volume()));
        when(candles.getCompletedFiveMinuteCandles(eq(4717), any())).thenReturn(bars);

        var result = service.evaluate(ceTrigger(), LocalDateTime.of(2026, 8, 19, 13, 10), 173.65d);

        assertThat(result.ready()).isFalse();
        assertThat(result.reason()).contains("two completed five-minute closes");
    }

    private void configureStrictMode() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minimumTime", LocalTime.of(9, 30));
        ReflectionTestUtils.setField(service, "maxVwapExtensionAtr", 1.25d);
    }

    private TriggerTradeRequestEntity ceTrigger() {
        return TriggerTradeRequestEntity.builder().id(1L).source("atr-pdh-pdl-strategy")
                .optionType("CE").entryPrice(173.6d).spotScripCode(4717).build();
    }

    private List<MStockIntradayCandleService.IntradayCandle> risingBars() {
        LocalDate date = LocalDate.of(2026, 8, 19);
        List<MStockIntradayCandleService.IntradayCandle> result = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            double close = 173.35d + (i * 0.005d);
            result.add(new MStockIntradayCandleService.IntradayCandle(date, LocalTime.of(9, 15).plusMinutes(i * 5L),
                    close - 0.04d, close + 0.08d, close - 0.08d, close, 1_000L));
        }
        return result;
    }
}
