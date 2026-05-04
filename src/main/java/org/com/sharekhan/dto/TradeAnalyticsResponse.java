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
    private List<RecentClosedTrade> recentClosedTrades;

    @Data
    @Builder
    public static class Filters {
        private Long userId;
        private LocalDate from;
        private LocalDate to;
        private String symbol;
        private String source;
        private Long brokerCredentialsId;
        private Boolean intraday;
    }

    @Data
    @Builder
    public static class Summary {
        private Double realizedPnl;
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
