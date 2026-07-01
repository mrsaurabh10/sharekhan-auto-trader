package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class TradeAnalyticsResponse {
    private Filters filters;
    private Summary summary;
    private List<SymbolAnalytics> bySymbol;
    private List<DailyAnalytics> byDay;
    private BacktestAnalytics backtest;
    private List<RecentClosedTrade> recentClosedTrades;
    private String aiNarrative;

    @Data
    @Builder
    public static class Filters {
        private Long userId;
        private LocalDate from;
        private LocalDate to;
        private String symbol;
        private String source;
        private List<String> sources;
        private String scope;
        private Long brokerCredentialsId;
        private Boolean intraday;
    }

    @Data
    @Builder
    public static class Summary {
        private Double realizedPnl;
        private Double brokerage;
        private Double governmentTaxes;
        private Double totalTradeCost;
        private Double effectiveRealizedPnl;
        private Integer totalClosedTrades;
        private Integer winningTrades;
        private Integer losingTrades;
        private Integer breakevenTrades;
        private Double winRate;
        private Double lossRate;
        private Double profitFactor;
        private Double averageWin;
        private Double averageLoss;
        private Double bestTradePnl;
        private Double worstTradePnl;
        private Double maxFundUseAtTime;
        private LocalDateTime maxFundUseAt;
        private Integer activeTradesAtMaxFundUse;
        private Integer openTrades;
        private Long openQuantity;
        private Integer rejectedTrades;
        private Integer failedTrades;
    }

    @Data
    @Builder
    public static class SymbolAnalytics {
        private String symbol;
        private Integer closedCount;
        private Double realizedPnl;
        private Double winRate;
        private Double averagePnl;
    }

    @Data
    @Builder
    public static class DailyAnalytics {
        private LocalDate date;
        private Integer closedCount;
        private Double realizedPnl;
        private Double cumulativeRealizedPnl;
    }

    @Data
    @Builder
    public static class BacktestAnalytics {
        private BacktestSummary summary;
        private List<BacktestDailyAnalytics> byDay;
    }

    @Data
    @Builder
    public static class BacktestSummary {
        private Integer totalTrades;
        private Integer comparableTrades;
        private Integer actualComparableTrades;
        private Integer failedTrades;
        private Double actualPnl;
        private Double oneMinutePnl;
        private Double fiveMinutePnl;
        private Double oneMinuteReentryPnl;
        private Double diffFiveMinusOne;
        private Double diffReentryMinusOne;
        private Double oneMinuteMinusActual;
        private Double fiveMinuteMinusActual;
        private Double oneMinuteReentryMinusActual;
        private Double oneMinuteAbsoluteError;
        private Double fiveMinuteAbsoluteError;
        private Double oneMinuteReentryAbsoluteError;
        private Integer oneMinuteCloserToActual;
        private Integer fiveMinuteCloserToActual;
        private Integer closerToActualTies;
        private Integer oneMinuteReentryComparableTrades;
        private LocalDateTime lastRunAt;
    }

    @Data
    @Builder
    public static class BacktestDailyAnalytics {
        private LocalDate date;
        private Integer totalTrades;
        private Integer comparableTrades;
        private Integer actualComparableTrades;
        private Integer failedTrades;
        private Double actualPnl;
        private Double oneMinutePnl;
        private Double fiveMinutePnl;
        private Double oneMinuteReentryPnl;
        private Double diffFiveMinusOne;
        private Double diffReentryMinusOne;
        private Double oneMinuteMinusActual;
        private Double fiveMinuteMinusActual;
        private Double oneMinuteReentryMinusActual;
        private Double oneMinuteAbsoluteError;
        private Double fiveMinuteAbsoluteError;
        private Double oneMinuteReentryAbsoluteError;
        private Integer oneMinuteCloserToActual;
        private Integer fiveMinuteCloserToActual;
        private Integer closerToActualTies;
        private Integer oneMinuteReentryComparableTrades;
    }

    @Data
    @Builder
    public static class RecentClosedTrade {
        private Long id;
        private String symbol;
        private Long quantity;
        private Double entryPrice;
        private Double exitPrice;
        private Double pnl;
        private String exitReason;
        private LocalDateTime exitedAt;
    }
}
