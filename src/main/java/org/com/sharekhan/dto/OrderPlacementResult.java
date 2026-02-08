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
    private Double executedPrice;
    private Double pnl; // for exit orders if available
}
