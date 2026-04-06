package org.com.sharekhan.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AfterHoursTargetOrderScheduler {

    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final TradeExecutionService tradeExecutionService;

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshTargetOrdersForAfterHours() {
        List<TriggeredTradeSetupEntity> targetOrders = triggeredTradeRepo.findByStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        if (targetOrders.isEmpty()) {
            log.debug("No TARGET_ORDER_PLACED trades found for after-hours refresh.");
            return;
        }

        int attempted = 0;
        int placed = 0;

        for (TriggeredTradeSetupEntity trade : targetOrders) {
            if (Boolean.TRUE.equals(trade.getIntraday())) {
                continue;
            }
            attempted++;
            try {
                if (tradeExecutionService.rescheduleTargetOrderAfterHours(trade.getId())) {
                    placed++;
                }
            } catch (Exception ex) {
                log.error("Failed to schedule after-hours target order for trade {}: {}", trade.getId(), ex.getMessage(), ex);
            }
        }

        log.info("After-hours target refresh complete. Candidates={}, placed={}", attempted, placed);
    }
}
