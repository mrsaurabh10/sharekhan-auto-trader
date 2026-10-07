package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.NseMarketCalendar;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class DailyIndexHistoryScheduler {
    private final StrategySupport support;
    private final StrategyCandleHistoryService rollingHistory;
    private final SharekhanHistoricalService sharekhan;
    private final MarketHistoryArchiveService archive;
    private final NseMarketCalendar calendar;
    private final AtomicBoolean running = new AtomicBoolean();
    @Value("${app.market-data.daily-history-enabled:true}") private boolean enabled;

    /** Capture intraday candles while they remain available, then reconcile at 19:00 IST. */
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "${app.market-data.daily-history-cron:0 0 19 * * *}", zone = "Asia/Kolkata")
    public void capture() {
        capture(LocalDateTime.now(StrategySupport.MARKET_ZONE));
    }

    void capture(LocalDateTime now) {
        if (!enabled || !calendar.isTradingDay(now.toLocalDate()) || !running.compareAndSet(false, true)) return;
        try {
            for (String symbol : List.of("NIFTY", "BANKNIFTY")) {
                try {
                    captureSymbol(symbol, now);
                } catch (Exception e) {
                    log.error("Daily index history failed for {} on {}", symbol, now.toLocalDate(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void captureSymbol(String symbol, LocalDateTime now) {
        ScriptMasterEntity script = support.resolveSpotScript(symbol);
        String key = script.getExchange().trim().toUpperCase(Locale.ROOT) + ":" + script.getScripCode();
        // Capture today's session only. Previous sessions stay in the permanent archive.
        // Locally captured and fresh intraday bars take priority over the fallback.
        try {
            List<StrategyCandle> historical = sharekhan.getRecentHistoricalCandles(script.getScripCode(), "5minute")
                    .stream().filter(c -> now.toLocalDate().equals(c.date()))
                    .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), null)).toList();
            archive.save(symbol, historical, "SHAREKHAN", now);
        } catch (Exception e) {
            log.warn("Sharekhan history unavailable for {}: {}", symbol, e.getMessage());
        }
        List<StrategyCandle> cached = rollingHistory.mergeAndSave(key, List.of(), now);
        archive.save(symbol, today(cached, now), "LIVE_CACHE", now);
        CandleLoad intraday = support.loadCandles(script);
        archive.save(symbol, today(intraday.candles(), now), "MSTOCK", now);
        // Keep the rolling indicator cache ready for the next session as well.
        rollingHistory.mergeAndSave(key, intraday.candles(), now);
        int count = archive.read(symbol, now.toLocalDate(), now.toLocalDate()).size();
        if (count < 75) {
            log.warn("Daily index history INCOMPLETE: symbol={} date={} stored={} expected=75 missing={} feedReason={}",
                    symbol, now.toLocalDate(), count, 75 - count, intraday.reason());
        } else {
            log.info("Daily index history COMPLETE: symbol={} date={} candles={}", symbol, now.toLocalDate(), count);
        }
    }

    private List<StrategyCandle> today(List<StrategyCandle> candles, LocalDateTime now) {
        return candles.stream().filter(c -> c != null && now.toLocalDate().equals(c.date())).toList();
    }
}
