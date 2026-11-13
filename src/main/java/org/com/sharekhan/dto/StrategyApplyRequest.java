package org.com.sharekhan.dto;

import lombok.Data;


@Data
public class StrategyApplyRequest {
    @com.fasterxml.jackson.annotation.JsonIgnore private Long subscriptionId;
    private org.com.sharekhan.strategy.run.StrategyRiskSettings riskSettings;
    private String templateId;
    private String symbol;
    private Integer lots;
    private java.math.BigDecimal investmentPerStock;
    private java.math.BigDecimal profitTargetPerLot;
    private Boolean intraday;
    private Long userId;
    private Long brokerCredentialsId;
    private String source;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean batmanResumeNoOrder;
    @com.fasterxml.jackson.annotation.JsonIgnore private String batmanRetryOfRunId;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean batmanMarketEntry;
    @com.fasterxml.jackson.annotation.JsonIgnore private java.time.LocalDate batmanManualEntryDay;
}
