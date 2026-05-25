package org.com.sharekhan.dto;

import lombok.Data;

@Data
public class StockAtrTradeRequest {
    private String stock;
    private Double entryPrice;
    private String direction;
    private Integer lots;
    private Integer expiryMonth;
    private Boolean intraday;
    private String source;
}
