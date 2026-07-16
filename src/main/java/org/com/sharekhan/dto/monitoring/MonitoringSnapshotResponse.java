package org.com.sharekhan.dto.monitoring;

import java.time.LocalDateTime;
import java.util.List;

public record MonitoringSnapshotResponse(
        LocalDateTime generatedAt,
        String timezone,
        List<MonitoringTradeSnapshot> activeTrades,
        List<MonitoringTradeSnapshot> closedToday) {
}
