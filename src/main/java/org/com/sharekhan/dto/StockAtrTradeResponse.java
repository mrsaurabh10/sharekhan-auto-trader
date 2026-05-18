package org.com.sharekhan.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StockAtrTradeResponse {
    private String status;
    private String stock;
    private String direction;
    private String optionType;
    private Double entryPrice;
    private Double atr;
    private Integer atrPeriod;
    private String candleInterval;
    private Double stopLoss;
    private Double target;
    private Double target1;
    private Double target2;
    private Double target3;
    private Double atr15m;
    private Double strikePrice;
    private String expiry;
    private Integer spotScripCode;
    private String message;
}
