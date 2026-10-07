package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.service.NseMarketCalendar;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Capture the full session even when the subscription's one-trade daily limit stopped evaluation. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupertrendCandleHistoryScheduler {
    private final StrategySubscriptionRepository repository;
    private final StrategySupport support;
    private final NseMarketCalendar calendar;

    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureSession() {
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        if (!calendar.isTradingDay(now.toLocalDate())) return;
        repository.findByStatusInOrderByIdDesc(List.of("ACTIVE", "TRIGGERED")).stream()
                .filter(subscription -> "ST_RSI_EMA_ADX_CE".equalsIgnoreCase(subscription.getTemplateId())
                        || "ST_RSI_EMA_ADX_PE".equalsIgnoreCase(subscription.getTemplateId()))
                .map(subscription -> subscription.getSymbol()).distinct().forEach(symbol -> {
                    try {
                        support.loadCompletedIndicatorCandles(support.resolveSpotScript(symbol), 50, now);
                    } catch (Exception e) {
                        log.warn("Unable to retain Supertrend session candles for {}: {}", symbol, e.getMessage());
                    }
                });
    }
}
