package org.com.sharekhan.dto.backtest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class BacktestReportResponse {
    private String status;
    private String reportId;
    private LocalDate from;
    private LocalDate to;
    private String source;
    private Integer tradeCount;
    private Integer resultCount;
    private Integer successCount;
    private Integer errorCount;
    private Double actualPnl;
    private Double backtestPnl;
    private List<IntervalSummary> intervals;
    private String downloadUrl;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime generatedAt;

    @Data
    @Builder
    public static class IntervalSummary {
        private String interval;
        private Integer resultCount;
        private Integer successCount;
        private Integer errorCount;
        private Double actualPnl;
        private Double backtestPnl;
    }
}
