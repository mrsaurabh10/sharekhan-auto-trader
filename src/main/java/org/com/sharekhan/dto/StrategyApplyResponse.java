package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Data;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;

@Data
@Builder
public class StrategyApplyResponse {
    private String status;
    private String message;
    private String templateId;
    private String symbol;
    private String direction;
    private Double openingRangeHigh;
    private Double openingRangeLow;
    private Double breakoutClose;
    private Double breakoutVolume;
    private Double averageVolume;
    private Double vwap;
    private Boolean volumeFilterPassed;
    private Boolean vwapFilterPassed;
    private Boolean volumeFilterSkipped;
    private Boolean vwapFilterSkipped;
    private TriggerRequest triggerRequest;
    private TriggerTradeRequestEntity tradeRequest;
}
