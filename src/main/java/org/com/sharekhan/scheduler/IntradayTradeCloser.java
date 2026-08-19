package org.com.sharekhan.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntradayTradeCloser {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final TriggeredTradeSetupRepository setupRepository;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final TradeExecutionService tradeExecutionService;
    private final LtpCacheService ltpCacheService;

    // Run every day at 15:20 IST, which is also the last permitted intraday entry time.
    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeIntradayTrades() {
        log.info("📆 Running intraday trade closer...");

        int cancelledEntries = tradeExecutionService.cancelPendingIntradayEntryOrdersAtCutoff();
        if (cancelledEntries > 0) {
            log.info("Cancelled {} pending intraday entry order(s) at the cutoff", cancelledEntries);
        }

        List<TriggeredTradeSetupEntity> intradayTrades = setupRepository
            .findByIntradayTrueAndStatus(TriggeredTradeStatus.EXECUTED);
        List<TriggeredTradeSetupEntity> intradayTargetOrders = setupRepository
            .findByIntradayTrueAndStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);

        for (TriggeredTradeSetupEntity trade : intradayTrades) {
            try {

                Double ltp = ltpCacheService.getLtp(trade.getScripCode());
                if (ltp == null) {
                    ltp = 0.0;
                    //TODO get the ltp from a different service
                }
                tradeExecutionService.squareOff(trade, ltp,"Intraday closing at 3:20 PM");
                log.info("💼 Closed intraday trade for {}", trade.getSymbol());
            } catch (Exception e) {
                log.error("❌ Failed to close intraday trade {}: {}", trade.getId(), e.getMessage(), e);
            }
        }

        for (TriggeredTradeSetupEntity trade : intradayTargetOrders) {
            try {
                Double ltp = ltpCacheService.getLtp(trade.getScripCode());
                if (ltp == null || ltp <= 0) {
                    log.warn("⚠️ Intraday target trade {} missing LTP; skipping modify for existing exit order {}", trade.getId(), trade.getExitOrderId());
                    continue;
                }
                boolean modified = tradeExecutionService.modifyExitOrderForIntradayClose(trade, ltp);
                if (modified) {
                    log.info("✏️ Updated intraday target exit order {} for trade {} to LTP {}", trade.getExitOrderId(), trade.getId(), ltp);
                }
            } catch (Exception e) {
                log.error("❌ Failed to update intraday target trade {}: {}", trade.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Removes all stale intraday requests, including rows with no creation timestamp.
     * Requests created after today's 15:30 IST close are preserved for the next day.
     * Executed trade rows are deliberately retained as the permanent audit trail.
     */
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void purgeTodayIntradayTradeRequests() {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        LocalDateTime cutoff = today.atTime(MARKET_CLOSE);
        int deleted = triggerTradeRequestRepository.deleteStaleIntradayRequestsCreatedBefore(cutoff);
        log.info("🧹 Removed {} stale intraday trade requests created before {} IST", deleted, cutoff);
    }
}
