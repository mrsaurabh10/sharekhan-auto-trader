package org.com.sharekhan.dto.backtest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class BacktestReplayResponse {
    private String status;
    private String message;
    private Long tradeSetupId;
    private TradeSnapshot trade;
    private ResolvedConfig resolved;
    private Result actual;
    private Result backtest;
    private List<Event> events;
    private List<String> warnings;

    @Data
    @Builder
    public static class TradeSnapshot {
        private String symbol;
        private Integer scripCode;
        private Integer spotScripCode;
        private String exchange;
        private Double strikePrice;
        private String optionType;
        private String expiry;
        private Long quantity;
        private Integer lots;
        private Integer originalLots;
        private Double entryPrice;
        private Double actualEntryPrice;
        private Double stopLoss;
        private Double target1;
        private Double target2;
        private Double target3;
        private Double trailingSl;
        private Boolean tslEnabled;
        private Boolean useSpotForEntry;
        private Boolean useSpotForSl;
        private Boolean useSpotForTarget;
        private LocalDateTime triggeredAt;
        private LocalDateTime entryAt;
        private LocalDateTime exitedAt;
        private Double exitPrice;
        private Double pnl;
        private String exitReason;
        private String status;
    }

    @Data
    @Builder
    public static class ResolvedConfig {
        private String interval;
        private Boolean intradayOnly;
        private String squareOffTime;
        private String sameCandlePolicy;
        private String triggerPricePolicy;
        private String executionPricePolicy;
        private Boolean reEntryOnStopLoss;
        private Integer maxReEntries;
        private String quantityMode;
        private Long quantity;
        private Integer lots;
        private String levelMode;
        private Double entryPriceForPnl;
        private Double stopLoss;
        private Double target1;
        private Double target2;
        private Double target3;
        private Double trailingSl;
        private Boolean tslEnabled;
        private String stopLossPriceSource;
        private String targetPriceSource;
    }

    @Data
    @Builder
    public static class Result {
        private LocalDateTime entryAt;
        private Double entryPrice;
        private LocalDateTime exitAt;
        private Double exitPrice;
        private String exitReason;
        private Long quantity;
        private Long remainingQuantity;
        private Double pnl;
        private Integer exitCount;
    }

    @Data
    @Builder
    public static class Event {
        private LocalDateTime at;
        private String type;
        private String reason;
        private String priceSource;
        private Double referencePrice;
        private Double optionPrice;
        private Double stopLoss;
        private Double target;
        private Long quantity;
        private Integer lots;
        private Double pnl;
    }
}
