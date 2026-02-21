package org.com.sharekhan.dto;

import lombok.Data;

@Data
public class UpdateTargetsRequest {
    private Double entryPrice;
    private Double stopLoss;
    private Double target1;
    private Double target2;
    private Double target3;
    private Long quantity;
    private Boolean intraday;
    private Long userId; // optional user id to validate ownership
    
    private Boolean useSpotPrice; // Legacy flag for backward compatibility

    // Granular spot price usage flags
    private Boolean useSpotForEntry;
    private Boolean useSpotForSl;
    private Boolean useSpotForTarget;

    private Integer spotScripCode; // Scrip code of the underlying spot
}
