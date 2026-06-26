package org.com.sharekhan.dto.backtest;

import lombok.Data;

@Data
public class BacktestReplayRequest {
    private Boolean intradayOnly;
    private String interval;
    private String squareOffTime;
    private String triggerPriceMode;
    private String sameCandlePolicy;
    private String triggerPricePolicy;
    private String executionPricePolicy;
    private Boolean reEntryOnStopLoss;
    private Integer maxReEntries;
    private QuantityOverride quantity;
    private LevelScenario levels;
    private Overrides overrides;

    @Data
    public static class QuantityOverride {
        /**
         * ACTUAL, FIXED_LOTS, or FIXED_QUANTITY.
         */
        private String mode;
        private Integer lots;
        private Long quantity;
    }

    @Data
    public static class LevelScenario {
        /**
         * ORIGINAL or R_MULTIPLE.
         */
        private String mode;
        private Double slR;
        private java.util.List<TargetRRule> targets;
    }

    @Data
    public static class TargetRRule {
        /**
         * T1, T2, T3, 1, 2, or 3.
         */
        private String target;
        private Double r;
    }

    @Data
    public static class Overrides {
        private LevelRule stopLoss;
        private LevelRule target1;
        private LevelRule target2;
        private LevelRule target3;
        private TrailingSlRule trailingSl;
    }

    @Data
    public static class LevelRule {
        /**
         * ORIGINAL, FIXED, POINTS_FROM_ENTRY, PERCENT_FROM_ENTRY, ATR_MULTIPLE, NONE.
         */
        private String type;

        /**
         * Used by FIXED rules.
         */
        private Double price;

        /**
         * Generic value for point or percent based rules.
         */
        private Double value;

        private Double points;
        private Double percent;
        private Double multiplier;
        private Integer period;

        /**
         * ORIGINAL, SPOT, or OPTION.
         */
        private String priceSource;
    }

    @Data
    public static class TrailingSlRule {
        private Boolean enabled;
        private String mode;
        private Double price;
    }
}
