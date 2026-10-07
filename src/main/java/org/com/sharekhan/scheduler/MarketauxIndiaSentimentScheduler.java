package org.com.sharekhan.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.MarketauxProperties;
import org.com.sharekhan.service.MarketauxIndiaSentimentCollector;
import org.com.sharekhan.service.NseMarketCalendar;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** Schedules exactly 100 (or fewer configured) provider calls evenly within the NSE cash session. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketauxIndiaSentimentScheduler {
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 20);
    private static final LocalTime END = LocalTime.of(15, 20);

    private final MarketauxProperties properties;
    private final NseMarketCalendar nseMarketCalendar;
    private final MarketauxIndiaSentimentCollector collector;
    private final ThreadPoolTaskScheduler applicationTaskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @PostConstruct
    void scheduleRemainingToday() {
        scheduleTradingDay(LocalDate.now(INDIA_ZONE));
    }

    // Prepare the day's exact timings before the first 09:20 request.
    @Scheduled(cron = "0 19 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleToday() {
        scheduleTradingDay(LocalDate.now(INDIA_ZONE));
    }

    private void scheduleTradingDay(LocalDate tradingDate) {
        if (!properties.isIndiaSentimentCollectionEnabled() || !properties.isEnabled()
                || !StringUtils.hasText(properties.getApiToken()) || !nseMarketCalendar.isTradingDay(tradingDate)) {
            return;
        }
        int calls = Math.min(Math.max(properties.getIndiaSentimentCallsPerDay(), 1), 100);
        LocalDateTime start = LocalDateTime.of(tradingDate, START);
        LocalDateTime end = LocalDateTime.of(tradingDate, END);
        LocalDateTime now = LocalDateTime.now(INDIA_ZONE);
        for (int slot = 0; slot < calls; slot++) {
            LocalDateTime scheduledAt = start.plusNanos(Duration.between(start, end).toNanos() * slot / calls);
            if (scheduledAt.isBefore(now)) {
                continue;
            }
            String taskKey = tradingDate + "-" + slot;
            int scheduledSlot = slot;
            scheduledTasks.computeIfAbsent(taskKey, ignored -> applicationTaskScheduler.schedule(() -> {
                try {
                    collector.collect(tradingDate, scheduledSlot, scheduledAt, properties.getIndiaSentimentArticlesPerCall());
                } finally {
                    scheduledTasks.remove(taskKey);
                }
            }, scheduledAt.atZone(INDIA_ZONE).toInstant()));
        }
        log.info("Prepared up to {} Marketaux India sentiment calls for {} between {} and {} IST", calls, tradingDate, START, END);
    }
}
