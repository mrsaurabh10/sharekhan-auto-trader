package org.com.sharekhan.dto;

import lombok.Data;


@Data
public class StrategyApplyRequest {
    private String templateId;
    private String symbol;
    private Integer lots;
    private java.math.BigDecimal investmentPerStock;
    private java.math.BigDecimal profitTargetPerLot;
    private Boolean intraday;
    private Long userId;
    private Long brokerCredentialsId;
    private String source;
}
