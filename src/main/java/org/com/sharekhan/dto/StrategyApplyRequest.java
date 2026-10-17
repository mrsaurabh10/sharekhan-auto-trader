package org.com.sharekhan.dto;

import lombok.Data;


@Data
public class StrategyApplyRequest {
    private String templateId;
    private String symbol;
    private Integer lots;
    private java.math.BigDecimal investmentPerStock;
    private Boolean intraday;
    private Long userId;
    private Long brokerCredentialsId;
    private String source;
}
