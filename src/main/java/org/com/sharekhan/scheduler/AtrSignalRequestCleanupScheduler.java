package org.com.sharekhan.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.service.TradeRequestCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AtrSignalRequestCleanupScheduler {

    private static final String ATR_SIGNAL_SOURCE = "atr-signal";

    private final TradeRequestCleanupService tradeRequestCleanupService;

    // Keep pruning pending ATR-signal entries from 15:00 until the 15:25 exit job.
    @Scheduled(cron = "0 0-24 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void cancelAtrSignalRequestsAtCutoff() {
        TradeRequestCleanupService.CleanupResult result =
                tradeRequestCleanupService.cancelPendingRequestsBySource(ATR_SIGNAL_SOURCE);
        log.info("ATR request cutoff complete at 15:00 IST: cancelled={} unsubscribeErrors={} errors={}",
                result.cancelled(), result.unsubscribeErrors(), result.errors());
    }
}
