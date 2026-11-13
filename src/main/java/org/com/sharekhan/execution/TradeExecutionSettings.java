package org.com.sharekhan.execution;

import jakarta.persistence.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Optional, broker-independent execution controls. Null settings preserve existing behavior. */
@Embeddable @Data
public class TradeExecutionSettings {
    public enum Direction { ABOVE, BELOW }
    private Integer timeExitMinutes;
    private Double timeExitProgressPrice;
    @Enumerated(EnumType.STRING) private Direction timeExitProgressDirection;
    private Boolean timeExitUseSpot;
    @JsonIgnore @Column(length=64) private String timeExitGroupId;
    /** Submit a marketable limit bounded by the existing configured entry-slippage allowance. */
    private Boolean marketableEntry;
    /** Simulator assumption only: false ignores optional displayed quantities, never broker fill status. */
    private Boolean requireDisplayedDepth;
    private Boolean spotStopOnTouch;
    private Boolean requireProfitableSpotTarget;

    public TradeExecutionSettings copy() {
        var c=new TradeExecutionSettings(); c.timeExitMinutes=timeExitMinutes;
        c.timeExitProgressPrice=timeExitProgressPrice;c.timeExitProgressDirection=timeExitProgressDirection;
        c.timeExitUseSpot=timeExitUseSpot;c.timeExitGroupId=timeExitGroupId;
        c.marketableEntry=marketableEntry;c.requireDisplayedDepth=requireDisplayedDepth;
        c.spotStopOnTouch=spotStopOnTouch;c.requireProfitableSpotTarget=requireProfitableSpotTarget;return c;
    }
    public static TradeExecutionSettings prepare(TradeExecutionSettings settings,Integer spotCode) {
        return prepare(settings,spotCode,null);
    }
    public static TradeExecutionSettings prepare(TradeExecutionSettings settings,Integer spotCode,String brokerProductType) {
        if(settings==null)return null;
        if(settings.getTimeExitMinutes()!=null && "BIGTRADEPLUS".equalsIgnoreCase(brokerProductType))
            throw new IllegalArgumentException("Time exits require normal orders; broker-managed brackets own their exit legs");
        var c=settings.copy();
        if(c.timeExitMinutes==null) {
            if(c.timeExitProgressPrice!=null || c.timeExitProgressDirection!=null || c.timeExitGroupId!=null)
                throw new IllegalArgumentException("Time-exit progress requires timeExitMinutes");
        } else {
            if(c.timeExitMinutes<1 || c.timeExitMinutes>1440)throw new IllegalArgumentException("timeExitMinutes must be 1..1440");
            if((c.timeExitProgressPrice==null)!=(c.timeExitProgressDirection==null))
                throw new IllegalArgumentException("Time-exit progress requires both price and direction");
            if(c.timeExitProgressPrice!=null && (!Double.isFinite(c.timeExitProgressPrice) || c.timeExitProgressPrice<=0))
                throw new IllegalArgumentException("Time-exit progress price must be finite and positive");
            if(c.timeExitProgressPrice!=null && Boolean.TRUE.equals(c.timeExitUseSpot) && spotCode==null)
                throw new IllegalArgumentException("Spot time-exit progress requires spotScripCode");
            if(c.timeExitGroupId==null)c.timeExitGroupId=java.util.UUID.randomUUID().toString();
            if(c.timeExitGroupId.isBlank() || c.timeExitGroupId.length()>64)throw new IllegalArgumentException("Invalid server time-exit group");
        }
        return c;
    }
}
