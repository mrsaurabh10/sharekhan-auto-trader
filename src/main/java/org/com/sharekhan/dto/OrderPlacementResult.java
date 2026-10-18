package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderPlacementResult {
    private boolean success;
    private String orderId;
    private String status; // e.g. "Fully Executed", "Pending", "Rejected"
    private String rejectionReason;
    // An explicit broker refusal, distinct from local validation or an unknown delivery outcome.
    private boolean definitiveBrokerRejection;
    private Double attemptedPrice;
    private Double executedPrice;
    private Long executedQuantity;
    private Double pnl; // for exit orders if available
}
