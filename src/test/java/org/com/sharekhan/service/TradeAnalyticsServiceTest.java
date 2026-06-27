package org.com.sharekhan.service;

import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.BacktestReplayResultRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeAnalyticsServiceTest {
    @Mock
    private TriggeredTradeSetupRepository tradeRepository;
    @Mock
    private BacktestReplayResultRepository backtestResultRepository;

    @InjectMocks
    private TradeAnalyticsService service;

    @Test
    void calculatesRealizedProfitabilityAndOrderHealth() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 30);
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
                closed(1L, "NIFTY", 1000.0, LocalDateTime.of(2026, 4, 3, 10, 0)),
                closed(2L, "NIFTY", -400.0, LocalDateTime.of(2026, 4, 4, 10, 0)),
                closed(3L, "BANKNIFTY", 0.0, LocalDateTime.of(2026, 4, 5, 10, 0)),
                open(4L, 75L, LocalDateTime.of(2026, 4, 6, 10, 0)),
                health(5L, TriggeredTradeStatus.REJECTED, LocalDateTime.of(2026, 4, 7, 10, 0)),
                health(6L, TriggeredTradeStatus.EXIT_FAILED, LocalDateTime.of(2026, 4, 8, 10, 0))
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(1L, from, to, null, null, null, null);

        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(600.0);
        assertThat(response.getSummary().getTotalClosedTrades()).isEqualTo(3);
        assertThat(response.getSummary().getWinningTrades()).isEqualTo(1);
        assertThat(response.getSummary().getLosingTrades()).isEqualTo(1);
        assertThat(response.getSummary().getBreakevenTrades()).isEqualTo(1);
        assertThat(response.getSummary().getWinRate()).isEqualTo(33.33);
        assertThat(response.getSummary().getLossRate()).isEqualTo(33.33);
        assertThat(response.getSummary().getProfitFactor()).isEqualTo(2.5);
        assertThat(response.getSummary().getAverageWin()).isEqualTo(1000.0);
        assertThat(response.getSummary().getAverageLoss()).isEqualTo(-400.0);
        assertThat(response.getSummary().getBestTradePnl()).isEqualTo(1000.0);
        assertThat(response.getSummary().getWorstTradePnl()).isEqualTo(-400.0);
        assertThat(response.getSummary().getMaxFundUseAtTime()).isEqualTo(5000.0);
        assertThat(response.getSummary().getMaxFundUseAt()).isEqualTo(LocalDateTime.of(2026, 4, 3, 9, 0));
        assertThat(response.getSummary().getActiveTradesAtMaxFundUse()).isEqualTo(1);
        assertThat(response.getSummary().getOpenTrades()).isEqualTo(1);
        assertThat(response.getSummary().getOpenQuantity()).isEqualTo(75L);
        assertThat(response.getSummary().getRejectedTrades()).isEqualTo(1);
        assertThat(response.getSummary().getFailedTrades()).isEqualTo(1);
        assertThat(response.getBySymbol()).hasSize(2);
        assertThat(response.getByDay()).hasSize(3);
        assertThat(response.getRecentClosedTrades()).hasSize(3);
    }

    @Test
    void returnsZeroMetricsWhenThereAreNoTrades() {
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of());

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                null,
                null,
                null,
                null
        );

        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(0.0);
        assertThat(response.getSummary().getTotalClosedTrades()).isZero();
        assertThat(response.getSummary().getWinRate()).isEqualTo(0.0);
        assertThat(response.getSummary().getLossRate()).isEqualTo(0.0);
        assertThat(response.getSummary().getProfitFactor()).isNull();
        assertThat(response.getSummary().getMaxFundUseAtTime()).isEqualTo(0.0);
        assertThat(response.getSummary().getMaxFundUseAt()).isNull();
        assertThat(response.getSummary().getActiveTradesAtMaxFundUse()).isZero();
        assertThat(response.getBySymbol()).isEmpty();
        assertThat(response.getByDay()).isEmpty();
    }

    @Test
    void calculatesPeakFundUseAndActiveTradeCountAtATime() {
        TriggeredTradeSetupEntity first = closed(1L, "NIFTY", 100.0, LocalDateTime.of(2026, 4, 3, 15, 20));
        first.setEntryAt(LocalDateTime.of(2026, 4, 2, 10, 0));
        first.setActualEntryPrice(125.50);
        first.setQuantity(20L);
        TriggeredTradeSetupEntity second = closed(2L, "BANKNIFTY", 200.0, LocalDateTime.of(2026, 4, 3, 15, 25));
        second.setEntryAt(LocalDateTime.of(2026, 4, 3, 10, 30));
        second.setEntryPrice(300.0);
        second.setQuantity(10L);
        TriggeredTradeSetupEntity third = closed(3L, "FINNIFTY", 300.0, LocalDateTime.of(2026, 4, 4, 15, 25));
        third.setEntryAt(LocalDateTime.of(2026, 4, 4, 10, 30));
        third.setEntryPrice(400.0);
        third.setQuantity(10L);
        TriggeredTradeSetupEntity rejected = health(4L, TriggeredTradeStatus.REJECTED, LocalDateTime.of(2026, 4, 4, 10, 30));
        rejected.setEntryPrice(500.0);
        rejected.setQuantity(100L);
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
                first,
                second,
                third,
                rejected
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                null,
                null,
                null,
                null
        );

        assertThat(response.getSummary().getMaxFundUseAtTime()).isEqualTo(5510.0);
        assertThat(response.getSummary().getMaxFundUseAt()).isEqualTo(LocalDateTime.of(2026, 4, 3, 10, 30));
        assertThat(response.getSummary().getActiveTradesAtMaxFundUse()).isEqualTo(2);
    }

    @Test
    void leavesProfitFactorNullWhenThereAreNoLosingTrades() {
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
                closed(1L, "NIFTY", 100.0, LocalDateTime.of(2026, 4, 3, 10, 0)),
                closed(2L, "BANKNIFTY", 200.0, LocalDateTime.of(2026, 4, 4, 10, 0))
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                null,
                null,
                null,
                null
        );

        assertThat(response.getSummary().getProfitFactor()).isNull();
        assertThat(response.getSummary().getAverageLoss()).isNull();
        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(300.0);
    }

    @Test
    void ignoresNullPnlAndFiltersClosedTradesByExitedAt() {
        TriggeredTradeSetupEntity nullPnl = closed(1L, "NIFTY", null, LocalDateTime.of(2026, 4, 3, 10, 0));
        TriggeredTradeSetupEntity exitedInsideTriggeredOutside = closed(2L, "NIFTY", 500.0, LocalDateTime.of(2026, 4, 5, 10, 0));
        exitedInsideTriggeredOutside.setTriggeredAt(LocalDateTime.of(2026, 3, 20, 10, 0));
        TriggeredTradeSetupEntity exitedOutside = closed(3L, "NIFTY", 250.0, LocalDateTime.of(2026, 5, 1, 10, 0));
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
                nullPnl,
                exitedInsideTriggeredOutside,
                exitedOutside
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                null,
                null,
                null,
                null
        );

        assertThat(response.getSummary().getTotalClosedTrades()).isEqualTo(1);
        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(500.0);
        assertThat(response.getRecentClosedTrades()).extracting(TradeAnalyticsResponse.RecentClosedTrade::getId).containsExactly(2L);
    }

    @Test
    void listsUserAnalyticsSourcesExcludingSimulatorByDefault() {
        when(tradeRepository.findAnalyticsSourcesByUserExcludingSimulator(eq(1L), eq("Simulator")))
                .thenReturn(Arrays.asList("telegram", " Manual ", "", null, "api"));

        List<String> sources = service.getAvailableSources(1L, null);

        assertThat(sources).containsExactly("api", "Manual", "telegram");
    }

    @Test
    void listsSimulatorSourcesForSimulatorScope() {
        when(tradeRepository.findAnalyticsSourcesBySimulator(eq("Simulator")))
                .thenReturn(List.of("simulator", "backtest"));

        List<String> sources = service.getAvailableSources(1L, "simulator");

        assertThat(sources).containsExactly("backtest", "simulator");
    }

    @Test
    void filtersAnalyticsByMultipleSources() {
        TriggeredTradeSetupEntity telegram = closed(1L, "NIFTY", 100.0, LocalDateTime.of(2026, 4, 3, 10, 0));
        telegram.setSource("telegram");
        TriggeredTradeSetupEntity adminUi = closed(2L, "NIFTY", 200.0, LocalDateTime.of(2026, 4, 4, 10, 0));
        adminUi.setSource("admin-ui");
        TriggeredTradeSetupEntity strategy = closed(3L, "NIFTY", 300.0, LocalDateTime.of(2026, 4, 5, 10, 0));
        strategy.setSource("strategy:orb");
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(List.of(telegram, adminUi, strategy));

        TradeAnalyticsResponse response = service.getTradeAnalyticsForSources(
                1L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                null,
                List.of("telegram", "admin-ui"),
                null,
                null,
                null
        );

        assertThat(response.getSummary().getTotalClosedTrades()).isEqualTo(2);
        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(300.0);
        assertThat(response.getFilters().getSources()).containsExactly("telegram", "admin-ui");
        assertThat(response.getRecentClosedTrades()).extracting(TradeAnalyticsResponse.RecentClosedTrade::getId)
                .containsExactly(2L, 1L);
    }

    @Test
    void includesSavedBacktestReplayAnalytics() {
        LocalDate from = LocalDate.of(2026, 6, 8);
        LocalDate to = LocalDate.of(2026, 6, 8);
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq("atr-signal"), eq(null), eq(true)))
                .thenReturn(List.of());
        when(backtestResultRepository.findByTradeDateBetween(eq(from), eq(to))).thenReturn(List.of(
                replay(10L, "1minute", "SUCCESS", 100.0, 50.0),
                replay(10L, "5minute", "SUCCESS", 130.0, 50.0),
                replay(11L, "BANKNIFTY", 2002, "1minute", "SUCCESS", -40.0, -100.0),
                replay(11L, "BANKNIFTY", 2002, "5minute", "SUCCESS", -80.0, -100.0),
                replay(12L, "FINNIFTY", 3003, "1minute", "ERROR", null, 0.0),
                replay(12L, "FINNIFTY", 3003, "5minute", "ERROR", null, 0.0)
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                from,
                to,
                null,
                "atr-signal",
                null,
                true
        );

        TradeAnalyticsResponse.BacktestSummary summary = response.getBacktest().getSummary();
        assertThat(summary.getTotalTrades()).isEqualTo(3);
        assertThat(summary.getComparableTrades()).isEqualTo(2);
        assertThat(summary.getActualComparableTrades()).isEqualTo(2);
        assertThat(summary.getFailedTrades()).isEqualTo(1);
        assertThat(summary.getActualPnl()).isEqualTo(-50.0);
        assertThat(summary.getOneMinutePnl()).isEqualTo(60.0);
        assertThat(summary.getFiveMinutePnl()).isEqualTo(50.0);
        assertThat(summary.getDiffFiveMinusOne()).isEqualTo(-10.0);
        assertThat(summary.getOneMinuteCloserToActual()).isEqualTo(1);
        assertThat(summary.getFiveMinuteCloserToActual()).isEqualTo(1);
        assertThat(response.getBacktest().getByDay()).hasSize(1);
    }

    @Test
    void analyticsSkipsTslChildClosedTrades() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 6, 19, 9, 20);
        TriggeredTradeSetupEntity root = closed(100L, "ITC", 500.0, LocalDateTime.of(2026, 6, 19, 10, 0));
        root.setSource("atr-signal");
        root.setScripCode(107866);
        root.setSpotScripCode(1660);
        root.setExchange("NF");
        root.setOptionType("CE");
        root.setStrikePrice(420.0);
        root.setExpiry("30/06/2026");
        root.setEntryAt(entryAt);
        root.setTriggeredAt(entryAt);
        TriggeredTradeSetupEntity child = closed(101L, "ITC", 1000.0, LocalDateTime.of(2026, 6, 19, 11, 0));
        child.setSource("atr-signal");
        child.setScripCode(107866);
        child.setSpotScripCode(1660);
        child.setExchange("NF");
        child.setOptionType("CE");
        child.setStrikePrice(420.0);
        child.setExpiry("30/06/2026");
        child.setEntryAt(entryAt);
        child.setTriggeredAt(entryAt);
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq("atr-signal"), eq(null), eq(null)))
                .thenReturn(List.of(child, root));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 6, 19),
                null,
                "atr-signal",
                null,
                null
        );

        assertThat(response.getSummary().getTotalClosedTrades()).isEqualTo(1);
        assertThat(response.getSummary().getRealizedPnl()).isEqualTo(500.0);
        assertThat(response.getRecentClosedTrades()).extracting(TradeAnalyticsResponse.RecentClosedTrade::getId)
                .containsExactly(100L);
    }

    @Test
    void backtestAnalyticsSkipsPersistedChildRowsForSameInstrument() {
        LocalDate from = LocalDate.of(2026, 6, 19);
        when(tradeRepository.findForAnalyticsByUserExcludingSimulator(eq(1L), eq("Simulator"), eq(null), eq("atr-signal"), eq(null), eq(true)))
                .thenReturn(List.of());
        BacktestReplayResultEntity rootOneMinute = replay(100L, "1minute", "SUCCESS", 500.0, 100.0);
        rootOneMinute.setSymbol("ITC");
        rootOneMinute.setScripCode(107866);
        rootOneMinute.setOptionType("CE");
        rootOneMinute.setStrikePrice(420.0);
        rootOneMinute.setExpiry("30/06/2026");
        rootOneMinute.setTradeDate(from);
        BacktestReplayResultEntity childOneMinute = replay(101L, "1minute", "SUCCESS", 1000.0, 200.0);
        childOneMinute.setSymbol("ITC");
        childOneMinute.setScripCode(107866);
        childOneMinute.setOptionType("CE");
        childOneMinute.setStrikePrice(420.0);
        childOneMinute.setExpiry("30/06/2026");
        childOneMinute.setTradeDate(from);
        BacktestReplayResultEntity rootFiveMinute = replay(100L, "5minute", "SUCCESS", 600.0, 100.0);
        rootFiveMinute.setSymbol("ITC");
        rootFiveMinute.setScripCode(107866);
        rootFiveMinute.setOptionType("CE");
        rootFiveMinute.setStrikePrice(420.0);
        rootFiveMinute.setExpiry("30/06/2026");
        rootFiveMinute.setTradeDate(from);
        when(backtestResultRepository.findByTradeDateBetween(eq(from), eq(from))).thenReturn(List.of(
                childOneMinute,
                rootOneMinute,
                rootFiveMinute
        ));

        TradeAnalyticsResponse response = service.getTradeAnalytics(
                1L,
                from,
                from,
                null,
                "atr-signal",
                null,
                true
        );

        TradeAnalyticsResponse.BacktestSummary summary = response.getBacktest().getSummary();
        assertThat(summary.getTotalTrades()).isEqualTo(1);
        assertThat(summary.getComparableTrades()).isEqualTo(1);
        assertThat(summary.getActualPnl()).isEqualTo(100.0);
        assertThat(summary.getOneMinutePnl()).isEqualTo(500.0);
        assertThat(summary.getFiveMinutePnl()).isEqualTo(600.0);
    }

    private TriggeredTradeSetupEntity closed(Long id, String symbol, Double pnl, LocalDateTime exitedAt) {
        return TriggeredTradeSetupEntity.builder()
                .id(id)
                .symbol(symbol)
                .quantity(50L)
                .entryPrice(100.0)
                .exitPrice(120.0)
                .pnl(pnl)
                .status(TriggeredTradeStatus.EXITED_SUCCESS)
                .triggeredAt(exitedAt.minusHours(1))
                .exitedAt(exitedAt)
                .build();
    }

    private TriggeredTradeSetupEntity open(Long id, Long quantity, LocalDateTime triggeredAt) {
        return TriggeredTradeSetupEntity.builder()
                .id(id)
                .symbol("NIFTY")
                .quantity(quantity)
                .status(TriggeredTradeStatus.EXECUTED)
                .triggeredAt(triggeredAt)
                .build();
    }

    private TriggeredTradeSetupEntity health(Long id, TriggeredTradeStatus status, LocalDateTime triggeredAt) {
        return TriggeredTradeSetupEntity.builder()
                .id(id)
                .symbol("NIFTY")
                .status(status)
                .triggeredAt(triggeredAt)
                .build();
    }

    private BacktestReplayResultEntity replay(Long tradeSetupId,
                                              String interval,
                                              String status,
                                              Double backtestPnl,
                                              Double actualPnl) {
        return replay(tradeSetupId, "NIFTY", null, interval, status, backtestPnl, actualPnl);
    }

    private BacktestReplayResultEntity replay(Long tradeSetupId,
                                              String symbol,
                                              Integer scripCode,
                                              String interval,
                                              String status,
                                              Double backtestPnl,
                                              Double actualPnl) {
        return BacktestReplayResultEntity.builder()
                .tradeSetupId(tradeSetupId)
                .appUserId(1L)
                .source("atr-signal")
                .symbol(symbol)
                .scripCode(scripCode)
                .tradeDate(LocalDate.of(2026, 6, 8))
                .interval(interval)
                .triggerPricePolicy("CLOSE")
                .squareOffTime("15:20")
                .intradayOnly(true)
                .status(status)
                .backtestPnl(backtestPnl)
                .actualPnl(actualPnl)
                .runAt(LocalDateTime.of(2026, 6, 8, 16, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 8, 16, 0))
                .build();
    }
}
