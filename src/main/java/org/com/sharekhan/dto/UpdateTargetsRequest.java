package org.com.sharekhan.dto;

import lombok.Data;

@Data
public class UpdateTargetsRequest {
    private Double stopLoss;
    private Double target1;
    private Double target2;
    private Double target3;
    private Long quantity;
    private Boolean intraday;
    private Long userId; // optional user id to validate ownership
}
