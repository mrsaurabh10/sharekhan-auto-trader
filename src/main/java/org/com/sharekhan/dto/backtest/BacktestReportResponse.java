package org.com.sharekhan.dto.backtest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private String downloadUrl;
    private LocalDateTime generatedAt;
}
