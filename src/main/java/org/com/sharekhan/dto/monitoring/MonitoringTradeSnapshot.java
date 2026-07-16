package org.com.sharekhan.dto.monitoring;

import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDateTime;

public record MonitoringTradeSnapshot(
        Long id,
        Long appUserId,
        String symbol,
        Integer scripCode,
        Integer spotScripCode,
        String exchange,
        String instrumentType,
        Double strikePrice,
        String optionType,
        String expiry,
        Long quantity,
        Integer lots,
        Double entryPrice,
        Double actualEntryPrice,
        Double stopLoss,
        Double target1,
        Double target2,
        Double target3,
        Double trailingSl,
        Boolean tslEnabled,
        Boolean useSpotForEntry,
        Boolean useSpotForSl,
        Boolean useSpotForTarget,
        String orderId,
        String exitOrderId,
        String exitReason,
        Boolean intraday,
        String source,
        TriggeredTradeStatus status,
        LocalDateTime triggeredAt,
        LocalDateTime entryAt,
        LocalDateTime exitOrderPlacedAt,
        LocalDateTime exitedAt,
        Double exitPrice,
        Double pnl,
        Double tradeCost,
        Double effectivePnl,
        Double instrumentLtp,
        LocalDateTime instrumentLtpObservedAt,
        Double spotLtp,
        LocalDateTime spotLtpObservedAt) {
}
