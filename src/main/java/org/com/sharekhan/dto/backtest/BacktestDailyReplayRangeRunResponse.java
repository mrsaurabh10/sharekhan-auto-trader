package org.com.sharekhan.dto.backtest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class BacktestDailyReplayRangeRunResponse {
    private String status;
    private LocalDate from;
    private LocalDate to;
    private String source;
    private Integer dayCount;
    private Integer tradeCount;
    private Integer resultCount;
    private Integer successCount;
    private Integer errorCount;
    private Integer skippedCount;
    private List<Long> failedTradeSetupIds;
    private List<BacktestDailyReplayRunResponse> days;
    private LocalDateTime runAt;
}
