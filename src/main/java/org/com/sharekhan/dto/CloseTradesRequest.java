package org.com.sharekhan.dto;

import lombok.Data;

@Data
public class CloseTradesRequest {
    private String instrument;
    private String symbol;
    private String optionType;
    private Double strikePrice;
    private String expiry;
    private Double price;
    private String reason;
}
