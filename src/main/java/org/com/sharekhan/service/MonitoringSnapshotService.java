package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dto.monitoring.MonitoringSnapshotResponse;
import org.com.sharekhan.dto.monitoring.MonitoringTradeSnapshot;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MonitoringSnapshotService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<TriggeredTradeStatus> ACTIVE_STATUSES = List.of(
            TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED,
            TriggeredTradeStatus.EXIT_TRIGGERED,
            TriggeredTradeStatus.EXIT_ORDER_PLACED);

    private final TriggeredTradeSetupRepository tradeRepository;
    private final LtpCacheService ltpCacheService;

    @Transactional(readOnly = true)
    public MonitoringSnapshotResponse snapshot() {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDate today = now.toLocalDate();
        List<MonitoringTradeSnapshot> active = tradeRepository.findByStatusIn(ACTIVE_STATUSES).stream()
                .sorted(Comparator.comparing(TriggeredTradeSetupEntity::getId))
                .map(this::toSnapshot)
                .toList();
        List<MonitoringTradeSnapshot> closed = tradeRepository
                .findByStatusAndExitedAtBetweenOrderByExitedAtAsc(
                        TriggeredTradeStatus.EXITED_SUCCESS,
                        today.atStartOfDay(),
                        today.atTime(LocalTime.MAX))
                .stream()
                .map(this::toSnapshot)
                .toList();
        return new MonitoringSnapshotResponse(now, IST.getId(), active, closed);
    }

    private MonitoringTradeSnapshot toSnapshot(TriggeredTradeSetupEntity trade) {
        Integer scripCode = trade.getScripCode();
        Integer spotScripCode = trade.getSpotScripCode();
        return new MonitoringTradeSnapshot(
                trade.getId(), trade.getAppUserId(), trade.getSymbol(), scripCode, spotScripCode,
                trade.getExchange(), trade.getInstrumentType(), trade.getStrikePrice(), trade.getOptionType(),
                trade.getExpiry(), trade.getQuantity(), trade.getLots(), trade.getEntryPrice(),
                trade.getActualEntryPrice(), trade.getStopLoss(), trade.getTarget1(), trade.getTarget2(),
                trade.getTarget3(), trade.getTrailingSl(), trade.getTslEnabled(), trade.getUseSpotForEntry(),
                trade.getUseSpotForSl(), trade.getUseSpotForTarget(), trade.getOrderId(), trade.getExitOrderId(),
                trade.getExitReason(), trade.getIntraday(), trade.getSource(), trade.getStatus(),
                trade.getTriggeredAt(), trade.getEntryAt(), trade.getExitOrderPlacedAt(), trade.getExitedAt(),
                trade.getExitPrice(), trade.getPnl(), trade.getTradeCost(), trade.getEffectivePnl(),
                scripCode != null ? ltpCacheService.getLtp(scripCode) : null,
                scripCode != null ? ltpCacheService.getObservedAt(scripCode) : null,
                spotScripCode != null ? ltpCacheService.getLtp(spotScripCode) : null,
                spotScripCode != null ? ltpCacheService.getObservedAt(spotScripCode) : null);
    }
}
