package org.com.sharekhan.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntradayTradeCloser {

    private final TriggeredTradeSetupRepository setupRepository;
    private final TradeExecutionService tradeExecutionService;
    private final LtpCacheService ltpCacheService;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;

    // Run every day at 15:25 IST
    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeIntradayTrades() {
        log.info("📆 Running intraday trade closer...");

        List<TriggeredTradeSetupEntity> intradayTrades = setupRepository
            .findByIntradayTrueAndStatus(TriggeredTradeStatus.EXECUTED);

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

        triggerTradeRequestRepository.deleteAll();
    }
}