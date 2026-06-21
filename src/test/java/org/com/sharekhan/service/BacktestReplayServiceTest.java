package org.com.sharekhan.service;

import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestReplayServiceTest {

    @Test
    void replaysFixedStopLossForSingleTrade() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository);

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(90.0);
        trade.setTarget1(120.0);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("5minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 104, 98, 102),
                        candle("2026-06-20", "09:25", 102, 106, 91, 94),
                        candle("2026-06-20", "09:30", 94, 97, 88, 89)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        BacktestReplayRequest.Overrides overrides = new BacktestReplayRequest.Overrides();
        BacktestReplayRequest.LevelRule stopLoss = new BacktestReplayRequest.LevelRule();
        stopLoss.setType("FIXED");
        stopLoss.setPrice(95.0);
        overrides.setStopLoss(stopLoss);
        request.setOverrides(overrides);

        BacktestReplayResponse response = service.replayTrade(1L, request);

        assertThat(response.getBacktest().getExitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(94.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(-450.0);
        assertThat(response.getResolved().getStopLoss()).isEqualTo(95.0);
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getType)
                .containsExactly("ENTRY", "EXIT");
    }

    @Test
    void replaysLiveCompatibleTslPartialBooking() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository);

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(150L);
        trade.setLots(2);
        trade.setOriginalLots(2);
        trade.setStopLoss(90.0);
        trade.setTarget1(110.0);
        trade.setTarget2(120.0);
        trade.setTslEnabled(true);
        when(tradeRepository.findById(2L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("5minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 105, 99, 102),
                        candle("2026-06-20", "09:25", 102, 111, 101, 112),
                        candle("2026-06-20", "09:30", 112, 113, 99, 99)
                ));

        BacktestReplayResponse response = service.replayTrade(2L, new BacktestReplayRequest());

        assertThat(response.getBacktest().getExitReason()).isEqualTo("TRAILING_SL_HIT");
        assertThat(response.getBacktest().getExitCount()).isEqualTo(2);
        assertThat(response.getBacktest().getPnl()).isEqualTo(825.0);
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getReason)
                .contains("TARGET_HIT_PARTIAL", "LIVE_COMPAT_TSL", "TRAILING_SL_HIT");
    }

    @Test
    void matchesNearestSpotCandleWhenTimestampsDoNotExactlyAlign() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository);

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setScripCode(1001);
        trade.setSpotScripCode(2002);
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setActualEntryPrice(10.0);
        trade.setEntryPrice(100.0);
        trade.setStopLoss(98.0);
        trade.setTarget1(101.0);
        trade.setUseSpotForEntry(true);
        trade.setUseSpotForSl(true);
        trade.setUseSpotForTarget(true);
        when(tradeRepository.findById(3L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candleWithSeconds("2026-06-20", "09:20:01", 10, 10.5, 9.8, 10.2),
                        candleWithSeconds("2026-06-20", "09:21:01", 10.2, 10.8, 10.1, 10.5),
                        candleWithSeconds("2026-06-20", "09:22:01", 10.5, 12.5, 10.4, 12)
                ));
        when(historicalService.getHistoricalCandles(eq(2002), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candleWithSeconds("2026-06-20", "09:20:59", 100, 100.4, 99.6, 100.2),
                        candleWithSeconds("2026-06-20", "09:21:59", 100.2, 101.2, 100.1, 101)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setInterval("1minute");
        BacktestReplayResponse response = service.replayTrade(3L, request);

        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(12.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(150.0);
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getReason)
                .containsExactly("ORIGINAL_ENTRY", "TARGET_HIT");
        assertThat(response.getEvents().get(1).getPriceSource()).isEqualTo("SPOT");
    }

    private TriggeredTradeSetupEntity baseTrade() {
        return TriggeredTradeSetupEntity.builder()
                .id(1L)
                .symbol("BANKNIFTY")
                .scripCode(1001)
                .exchange("NF")
                .strikePrice(52000.0)
                .optionType("CE")
                .expiry("30/06/2026")
                .entryPrice(100.0)
                .actualEntryPrice(100.0)
                .entryAt(LocalDateTime.of(2026, 6, 20, 9, 20))
                .triggeredAt(LocalDateTime.of(2026, 6, 20, 9, 20))
                .status(TriggeredTradeStatus.EXITED_SUCCESS)
                .intraday(true)
                .build();
    }

    private ScriptMasterEntity script(int lotSize) {
        return ScriptMasterEntity.builder()
                .scripCode(1001)
                .exchange("NF")
                .tradingSymbol("BANKNIFTY")
                .lotSize(lotSize)
                .build();
    }

    private SharekhanHistoricalService.HistoricalCandle candle(String date,
                                                               String time,
                                                               double open,
                                                               double high,
                                                               double low,
                                                               double close) {
        return new SharekhanHistoricalService.HistoricalCandle(
                LocalDate.parse(date),
                LocalTime.parse(time + ":00"),
                open,
                high,
                low,
                close
        );
    }

    private SharekhanHistoricalService.HistoricalCandle candleWithSeconds(String date,
                                                                          String time,
                                                                          double open,
                                                                          double high,
                                                                          double low,
                                                                          double close) {
        return new SharekhanHistoricalService.HistoricalCandle(
                LocalDate.parse(date),
                LocalTime.parse(time),
                open,
                high,
                low,
                close
        );
    }
}
