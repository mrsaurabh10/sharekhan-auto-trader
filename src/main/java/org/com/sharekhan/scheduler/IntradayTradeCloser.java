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

@Slf4j
@Component
@RequiredArgsConstructor
public class IntradayTradeCloser {

    private final TriggeredTradeSetupRepository setupRepository;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final TradeExecutionService tradeExecutionService;
    private final LtpCacheService ltpCacheService;

    // Run every day at 15:25 IST
    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeIntradayTrades() {
        log.info("📆 Running intraday trade closer...");

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
                tradeExecutionService.squareOff(trade, ltp,"Intraday closing at 3:25 PM");
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
     * Keeps the day's requests available for post-market investigation, then removes
     * the intraday-only request rows after the analysis window has ended.
     * Executed trade rows are deliberately retained as the permanent audit trail.
     */
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void purgeTodayIntradayTradeRequests() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDateTime dayStart = today.atStartOfDay();
        int deleted = triggerTradeRequestRepository.deleteIntradayRequestsCreatedBetween(dayStart, dayStart.plusDays(1));
        log.info("🧹 Removed {} intraday trade requests created on {} after the analysis window", deleted, today);
    }
}
