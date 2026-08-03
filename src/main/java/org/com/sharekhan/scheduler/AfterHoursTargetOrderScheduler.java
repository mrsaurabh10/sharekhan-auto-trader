package org.com.sharekhan.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.NseMarketCalendar;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AfterHoursTargetOrderScheduler {

    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final TradeExecutionService tradeExecutionService;
    private final NseMarketCalendar nseMarketCalendar;

    /**
     * Delivery exit orders are day-validity orders. Once the market has closed
     * and the broker reports one as inactive, release the local exit state for
     * the next session rather than submitting a new after-market target order.
     */
    @Scheduled(cron = "0 5 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshTargetOrdersForAfterHours() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        if (!nseMarketCalendar.isTradingDay(LocalDate.now(zone))) {
            log.debug("Skipping end-of-day delivery exit reset because today is not an NSE trading day.");
            return;
        }

        List<TriggeredTradeSetupEntity> exitStateTrades = triggeredTradeRepo.findNonIntradayExitStateTrades(List.of(
                TriggeredTradeStatus.EXIT_TRIGGERED,
                TriggeredTradeStatus.EXIT_ORDER_PLACED,
                TriggeredTradeStatus.TARGET_ORDER_PLACED));
        if (exitStateTrades.isEmpty()) {
            log.debug("No non-intraday exit-order states found for end-of-day reset.");
            return;
        }

        int reset = 0;
        int skipped = 0;

        for (TriggeredTradeSetupEntity trade : exitStateTrades) {
            try {
                TradeExecutionService.EndOfDayExitResetResult result =
                        tradeExecutionService.resetNonIntradayExitOrderIfInactive(trade.getId());
                if (result == TradeExecutionService.EndOfDayExitResetResult.RESET) {
                    reset++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                skipped++;
                log.error("Failed end-of-day exit reset for trade {}: {}", trade.getId(), ex.getMessage(), ex);
            }
        }

        log.info("End-of-day delivery exit reset complete. Candidates={}, reset={}, skipped={}",
                exitStateTrades.size(), reset, skipped);
    }

    /**
     * Recreate the day-valid broker target orders for eligible F&O option
     * delivery trades released by the previous session's end-of-day reset.
     */
    @Scheduled(cron = "0 16 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void rearmOptionPriceTargetsAtMarketOpen() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        if (!nseMarketCalendar.isTradingDay(LocalDate.now(zone))) {
            return;
        }
        int rearmed = tradeExecutionService.rearmNonIntradayOptionPriceTargetsAtMarketOpen();
        log.info("Market-open F&O option target rearm complete. Re-armed={}", rearmed);
    }

    /**
     * Signals received before the cash session are persisted immediately but
     * their broker-side entry trigger is submitted only once NSE is open.
     */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void submitPendingStockBazaariEquityEntriesAtMarketOpen() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        if (!nseMarketCalendar.isTradingDay(LocalDate.now(zone))) {
            return;
        }
        int submitted = tradeExecutionService.placePendingStockBazaariEquityEntriesAtMarketOpen();
        log.info("Market-open StockBazaari equity entry submission complete. Candidates={}", submitted);
    }
}
