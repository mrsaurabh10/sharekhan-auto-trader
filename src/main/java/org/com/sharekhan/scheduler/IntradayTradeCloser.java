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

    // Square off intraday positions at 15:35 IST. The entry cutoff remains independent.
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
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
                tradeExecutionService.squareOff(trade, ltp,"INTRADAY_CLOSE");
                log.info("💼 Closed intraday trade for {}", trade.getSymbol());
            } catch (Exception e) {
                log.error("❌ Failed to close intraday trade {}: {}", trade.getId(), e.getMessage(), e);
            }
        }

        for (TriggeredTradeSetupEntity trade : intradayTargetOrders) {
            try {
                boolean started = tradeExecutionService.startIntradayExitChase(trade);
                if (started) {
                    log.info("Intraday exit chase scheduled for trade {} existing order {}", trade.getId(), trade.getExitOrderId());
                }
            } catch (Exception e) {
                log.error("❌ Failed to update intraday target trade {}: {}", trade.getId(), e.getMessage(), e);
            }
        }
    }

    /** Cash closes at 15:30; retry before close for late fills or failed first attempts. */
    @Scheduled(cron = "0 21,24,27 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeCashIntradayTrades() {
        for (TriggeredTradeStatus status : List.of(TriggeredTradeStatus.EXECUTED, TriggeredTradeStatus.TARGET_ORDER_PLACED)) {
            for (TriggeredTradeSetupEntity trade : setupRepository.findByIntradayTrueAndStatus(status)) {
                if (!"NC".equalsIgnoreCase(trade.getExchange()) && !"BC".equalsIgnoreCase(trade.getExchange())) continue;
                // BIGTRADEPLUS brackets have their own broker-managed exit lifecycle.
                if ("BIGTRADEPLUS".equalsIgnoreCase(trade.getBrokerProductType())) continue;
                try {
                    if (status == TriggeredTradeStatus.TARGET_ORDER_PLACED) {
                        tradeExecutionService.startIntradayExitChase(trade);
                    } else {
                        Double ltp = ltpCacheService.getLtp(trade.getScripCode());
                        tradeExecutionService.squareOff(trade, ltp == null ? 0d : ltp, "INTRADAY_CLOSE");
                    }
                } catch (Exception e) {
                    log.error("Cash intraday close failed for trade {}: {}", trade.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Removes stale same-day requests, including non-intraday requests and rows with no creation timestamp.
     * Only non-intraday PLACED_PENDING_CONFIRMATION requests and requests created at or after today's 15:30 IST close are preserved.
     * Executed trade setup rows are deliberately retained as the permanent audit trail.
     */
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void purgeTodayIntradayTradeRequests() {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        LocalDateTime cutoff = today.atTime(MARKET_CLOSE);
        int deleted = triggerTradeRequestRepository.deleteStaleRequestsCreatedBeforePreservingNonIntradayStatus(
                cutoff, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        log.info("🧹 Removed {} stale trade requests created before {} IST, preserving non-intraday PLACED_PENDING_CONFIRMATION", deleted, cutoff);
    }
}
