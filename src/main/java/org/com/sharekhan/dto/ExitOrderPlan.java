package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ExitOrderPlan {

    public enum ExitStyle {
        STOP_LOSS_LIMIT,
        QUICK_LIMIT,
        FORCE_MARKET
    }

    ExitStyle style;
    double limitPrice;
    Double triggerPrice;
    String reasonTag;

    public boolean hasTrigger() {
        return triggerPrice != null && triggerPrice > 0;
    }
}

