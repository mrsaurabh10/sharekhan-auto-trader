package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockIntradayCandleService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategySupportTest {

    @Test
    void combinesMStockWithSharekhanHistoryWhenIntradayCandlesAreInsufficient() {
        MStockIntradayCandleService mStockIntradayCandleService = mock(MStockIntradayCandleService.class);
        SharekhanHistoricalService sharekhanHistoricalService = mock(SharekhanHistoricalService.class);
        StrategySupport support = support(mStockIntradayCandleService, sharekhanHistoricalService);

        ScriptMasterEntity spotScript = spotScript("NIFTY", "NC", 20000);
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        LocalDate previousDay = today.minusDays(1);

        when(mStockIntradayCandleService.getIntradayCandles("NSE", "26000", "5minute"))
                .thenReturn(List.of(
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 20), 101, 103, 100, 102, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 25), 102, 104, 101, 103, 1_200L)
                ));
        when(sharekhanHistoricalService.getHistoricalCandles(eq(20000), eq("5minute"), any(), eq(today)))
                .thenReturn(List.of(
                        new SharekhanHistoricalService.HistoricalCandle(previousDay, LocalTime.of(15, 20), 95, 96, 94, 95.5),
                        new SharekhanHistoricalService.HistoricalCandle(today, LocalTime.of(9, 20), 99, 100, 98, 99.5)
                ));

        CandleLoad result = support.loadCandlesWithHistoricalFallback(spotScript, 3);

        assertThat(result.candles()).hasSize(3);
        assertThat(result.candles().get(0).date()).isEqualTo(previousDay);
        assertThat(result.candles().get(2).time()).isEqualTo(LocalTime.of(9, 25));
        assertThat(result.candles().get(1).close()).isEqualTo(102); // overlapping timestamp prefers MStock
        assertThat(result.hasVolume()).isTrue();
    }

    @Test
    void skipsSharekhanFallbackWhenMStockAlreadyHasEnoughCandles() {
        MStockIntradayCandleService mStockIntradayCandleService = mock(MStockIntradayCandleService.class);
        SharekhanHistoricalService sharekhanHistoricalService = mock(SharekhanHistoricalService.class);
        StrategySupport support = support(mStockIntradayCandleService, sharekhanHistoricalService);

        ScriptMasterEntity spotScript = spotScript("NIFTY", "NC", 20000);
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);

        when(mStockIntradayCandleService.getIntradayCandles("NSE", "26000", "5minute"))
                .thenReturn(List.of(
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 20), 100, 101, 99, 100.5, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 25), 101, 102, 100, 101.5, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 30), 102, 103, 101, 102.5, 1_000L)
                ));

        CandleLoad result = support.loadCandlesWithHistoricalFallback(spotScript, 3);

        assertThat(result.candles()).hasSize(3);
        verify(sharekhanHistoricalService, never()).getHistoricalCandles(any(), any(), any(), any());
    }

    private StrategySupport support(MStockIntradayCandleService mStockIntradayCandleService,
                                    SharekhanHistoricalService sharekhanHistoricalService) {
        return new StrategySupport(
                mock(ScriptMasterRepository.class),
                mock(MStockInstrumentResolver.class),
                mock(MStockInstrumentRepository.class),
                mStockIntradayCandleService,
                sharekhanHistoricalService,
                mock(TradeExecutionService.class),
                mock(TriggerTradeRequestRepository.class)
        );
    }

    private ScriptMasterEntity spotScript(String symbol, String exchange, Integer scripCode) {
        return ScriptMasterEntity.builder()
                .tradingSymbol(symbol)
                .exchange(exchange)
                .scripCode(scripCode)
                .build();
    }
}
