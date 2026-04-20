package org.com.sharekhan.dto;


import lombok.Data;

@Data
public class TriggerRequest {
    private String instrument;
    private String exchange;
    private Double entryPrice;
    private Double stopLoss;
    private Double target1;
    private Double target2;
    private Double target3;
    private Double trailingSl;
    private Boolean tslEnabled; // Flag to enable TSL logic
    private Integer quantity; // in lots
    private Integer lots; // Explicit lots field
    private Double strikePrice;
    private String optionType;
    private String expiry; // format: 31/07/2025
    private Boolean intraday;
    // optional user id (customer id) - if absent backend will fallback to default
    private Long userId;
    // optional broker credentials id to use for this request (broker_credentials.id)
    private Long brokerCredentialsId;
    
    private Boolean useSpotPrice; // Legacy flag for backward compatibility
    
    // Granular spot price usage flags
    private Boolean useSpotForEntry;
    private Boolean useSpotForSl;
    private Boolean useSpotForTarget;

    private Integer spotScripCode; // Scrip code of the underlying spot

    // optional source to identify where the request originated
    private String source;

    private Boolean quickTrade;

    private String action;
}
