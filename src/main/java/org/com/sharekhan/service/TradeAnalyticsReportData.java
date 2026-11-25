package org.com.sharekhan.service;

import org.com.sharekhan.dto.TradeAnalyticsResponse;
import java.time.LocalDateTime;
import java.util.List;

/** Full, scoped export snapshot; separate from the dashboard's ten recent trades. */
public record TradeAnalyticsReportData(TradeAnalyticsResponse analytics, List<Position> positions) {
    public record Position(Long id, String symbol, String source, String side, String status, Long quantity,
                           Double entryPrice, Double exitPrice, LocalDateTime enteredAt, LocalDateTime exitedAt,
                           Double pnl, Double cost, Double effectivePnl, String exitReason) {}
}
