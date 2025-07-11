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
    private Integer quantity; // in lots
    private Double strikePrice;
    private String optionType;
    private String expiry; // format: 31/07/2025
}
