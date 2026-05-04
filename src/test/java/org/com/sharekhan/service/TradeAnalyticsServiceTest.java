package org.com.sharekhan.service;

import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeAnalyticsServiceTest {
    @Mock
    private TriggeredTradeSetupRepository tradeRepository;

    @InjectMocks
    private TradeAnalyticsService service;

    @Test
    void calculatesRealizedProfitabilityAndOrderHealth() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 30);
        when(tradeRepository.findForAnalytics(eq(1L), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
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
        when(tradeRepository.findForAnalytics(eq(1L), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of());

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
        assertThat(response.getBySymbol()).isEmpty();
        assertThat(response.getByDay()).isEmpty();
    }

    @Test
    void leavesProfitFactorNullWhenThereAreNoLosingTrades() {
        when(tradeRepository.findForAnalytics(eq(1L), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
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
        when(tradeRepository.findForAnalytics(eq(1L), eq(null), eq(null), eq(null), eq(null))).thenReturn(List.of(
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
}
