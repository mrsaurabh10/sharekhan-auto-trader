package org.com.sharekhan.dto.backtest;

import lombok.Data;

@Data
public class BacktestReplayRequest {
    private Boolean intradayOnly;
    private String interval;
    private String squareOffTime;
    private String sameCandlePolicy;
    private String triggerPricePolicy;
    private String executionPricePolicy;
    private Overrides overrides;

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
