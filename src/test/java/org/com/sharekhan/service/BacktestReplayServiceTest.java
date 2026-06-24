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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BacktestReplayServiceTest {

    @Test
    void replaysFixedStopLossForSingleTrade() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

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
        assertThat(response.getResolved().getTriggerPricePolicy()).isEqualTo("LTP");
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getType)
                .containsExactly("ENTRY", "EXIT");
    }

    @Test
    void closeTriggerPolicyRequiresStopLossCloseConfirmation() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(95.0);
        trade.setTarget1(120.0);
        when(tradeRepository.findById(5L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("5minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 104, 98, 102),
                        candle("2026-06-20", "09:25", 102, 106, 94, 100),
                        candle("2026-06-20", "09:30", 100, 104, 93, 94)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setTriggerPricePolicy("CLOSE");

        BacktestReplayResponse response = service.replayTrade(5L, request);

        assertThat(response.getResolved().getTriggerPricePolicy()).isEqualTo("CLOSE");
        assertThat(response.getBacktest().getExitAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 9, 30));
        assertThat(response.getBacktest().getExitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(94.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(-450.0);
    }

    @Test
    void closeTriggerPolicyRequiresTargetCloseConfirmation() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(90.0);
        trade.setTarget1(110.0);
        when(tradeRepository.findById(6L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("5minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 105, 99, 102),
                        candle("2026-06-20", "09:25", 102, 111, 101, 108),
                        candle("2026-06-20", "09:30", 108, 112, 107, 111)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setTriggerPricePolicy("close");

        BacktestReplayResponse response = service.replayTrade(6L, request);

        assertThat(response.getResolved().getTriggerPricePolicy()).isEqualTo("CLOSE");
        assertThat(response.getBacktest().getExitAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 9, 30));
        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(111.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(825.0);
    }

    @Test
    void reEntersOnceAfterStopLossWhenCloseRecoversEntryPrice() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(95.0);
        trade.setTarget1(110.0);
        when(tradeRepository.findById(8L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 101, 99, 100),
                        candle("2026-06-20", "09:21", 100, 100, 94, 94),
                        candle("2026-06-20", "09:22", 94, 100, 93, 99),
                        candle("2026-06-20", "09:23", 99, 102, 98, 101),
                        candle("2026-06-20", "09:24", 101, 113, 100, 112)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setInterval("1minute");
        request.setTriggerPricePolicy("CLOSE");
        request.setReEntryOnStopLoss(true);
        request.setMaxReEntries(1);

        BacktestReplayResponse response = service.replayTrade(8L, request);

        assertThat(response.getResolved().getReEntryOnStopLoss()).isTrue();
        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getExitCount()).isEqualTo(2);
        assertThat(response.getBacktest().getPnl()).isEqualTo(375.0);
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getReason)
                .containsExactly("ORIGINAL_ENTRY", "STOP_LOSS_HIT", "RE_ENTRY_AFTER_STOP_LOSS", "TARGET_HIT");
    }

    @Test
    void doesNotReEnterAfterTrailingStopLossHit() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(150L);
        trade.setLots(2);
        trade.setOriginalLots(2);
        trade.setStopLoss(90.0);
        trade.setTarget1(110.0);
        trade.setTarget2(120.0);
        trade.setTslEnabled(true);
        when(tradeRepository.findById(9L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:20", 100, 105, 99, 102),
                        candle("2026-06-20", "09:21", 102, 112, 101, 111),
                        candle("2026-06-20", "09:22", 111, 112, 98, 99),
                        candle("2026-06-20", "09:23", 99, 112, 98, 111)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setInterval("1minute");
        request.setTriggerPricePolicy("CLOSE");
        request.setReEntryOnStopLoss(true);
        request.setMaxReEntries(1);

        BacktestReplayResponse response = service.replayTrade(9L, request);

        assertThat(response.getBacktest().getExitReason()).isEqualTo("TRAILING_SL_HIT");
        assertThat(response.getBacktest().getExitCount()).isEqualTo(2);
        assertThat(response.getEvents()).extracting(BacktestReplayResponse.Event::getReason)
                .doesNotContain("RE_ENTRY_AFTER_STOP_LOSS");
    }

    @Test
    void replaysLiveCompatibleTslPartialBooking() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

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
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

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

    @Test
    void doesNotUsePreEntrySpotCandleForPostEntryTriggerCheck() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setScripCode(1001);
        trade.setSpotScripCode(2002);
        trade.setEntryAt(LocalDateTime.of(2026, 6, 20, 9, 8, 20));
        trade.setTriggeredAt(LocalDateTime.of(2026, 6, 20, 9, 8, 20));
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setActualEntryPrice(10.0);
        trade.setEntryPrice(100.0);
        trade.setStopLoss(99.0);
        trade.setTarget1(102.0);
        trade.setUseSpotForEntry(true);
        trade.setUseSpotForSl(true);
        trade.setUseSpotForTarget(true);
        when(tradeRepository.findById(7L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candleWithSeconds("2026-06-20", "09:08:28", 10, 10.5, 9.8, 10.5),
                        candleWithSeconds("2026-06-20", "09:09:45", 10.5, 11.2, 10.4, 11)
                ));
        when(historicalService.getHistoricalCandles(eq(2002), eq("1minute"), any(), any()))
                .thenReturn(List.of(
                        candleWithSeconds("2026-06-20", "09:07:59", 100, 100.2, 98.0, 98.5),
                        candleWithSeconds("2026-06-20", "09:08:59", 100.0, 100.5, 99.8, 100.2),
                        candleWithSeconds("2026-06-20", "09:09:59", 100.2, 103.0, 100.1, 103.0)
                ));

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setInterval("1minute");
        request.setTriggerPricePolicy("CLOSE");
        BacktestReplayResponse response = service.replayTrade(7L, request);

        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getExitAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 9, 9, 45));
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(11.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(75.0);
    }

    @Test
    void skipsCandlesBeforeEntryTime() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mock(MStockHistoricalService.class));

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setEntryAt(LocalDateTime.of(2026, 6, 20, 9, 18));
        trade.setTriggeredAt(LocalDateTime.of(2026, 6, 20, 9, 18));
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(95.0);
        trade.setTarget1(110.0);
        when(tradeRepository.findById(4L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(historicalService.getHistoricalCandles(eq(1001), eq("5minute"), any(), any()))
                .thenReturn(List.of(
                        candle("2026-06-20", "09:15", 100, 101, 90, 92),
                        candle("2026-06-20", "09:20", 100, 112, 99, 111)
                ));

        BacktestReplayResponse response = service.replayTrade(4L, new BacktestReplayRequest());

        assertThat(response.getBacktest().getExitAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 9, 20));
        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getPnl()).isEqualTo(825.0);
    }

    @Test
    void usesMStockHistoricalCandlesBeforeSharekhan() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        MStockHistoricalService mStockHistoricalService = mock(MStockHistoricalService.class);
        BacktestReplayService service = new BacktestReplayService(tradeRepository, historicalService, scriptMasterRepository, mStockHistoricalService);

        TriggeredTradeSetupEntity trade = baseTrade();
        trade.setQuantity(75L);
        trade.setLots(1);
        trade.setStopLoss(95.0);
        trade.setTarget1(110.0);
        when(tradeRepository.findById(10L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(1001)).thenReturn(script(75));
        when(mStockHistoricalService.getHistoricalCandles(eq(1001), any(), any(), any(), any(), any(), eq("1minute"), any(), any()))
                .thenReturn(MStockHistoricalService.HistoricalResponse.builder()
                        .status("success")
                        .count(2)
                        .candles(List.of(
                                mstockCandle("2026-06-20", "09:20", 100, 101, 99, 100),
                                mstockCandle("2026-06-20", "09:21", 100, 112, 99, 111)
                        ))
                        .build());

        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setInterval("1minute");
        request.setTriggerPricePolicy("CLOSE");
        BacktestReplayResponse response = service.replayTrade(10L, request);

        assertThat(response.getBacktest().getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(response.getBacktest().getExitPrice()).isEqualTo(111.0);
        assertThat(response.getBacktest().getPnl()).isEqualTo(825.0);
        verifyNoInteractions(historicalService);
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

    private MStockHistoricalService.HistoricalCandle mstockCandle(String date,
                                                                  String time,
                                                                  double open,
                                                                  double high,
                                                                  double low,
                                                                  double close) {
        return MStockHistoricalService.HistoricalCandle.builder()
                .date(LocalDate.parse(date))
                .time(LocalTime.parse(time + ":00"))
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .build();
    }
}
