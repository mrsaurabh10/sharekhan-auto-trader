package org.com.sharekhan.dto.backtest;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class BacktestReportRequest {
    private LocalDate from;
    private LocalDate to;
    private String source = "atr-signal";
    private List<String> intervals;
    private String squareOffTime;
    private String triggerPriceMode;
    private String executionPricePolicy;
    private Boolean intradayOnly;
    private Boolean reEntryOnStopLoss;
    private Integer maxReEntries;
    private BacktestReplayRequest.QuantityOverride quantity;
    private BacktestReplayRequest.LevelScenario levels;
    private BacktestReplayRequest.Overrides overrides;
}
