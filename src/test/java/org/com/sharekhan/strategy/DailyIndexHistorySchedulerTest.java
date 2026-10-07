package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.NseMarketCalendar;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DailyIndexHistorySchedulerTest {
    private final StrategySupport support = mock(StrategySupport.class);
    private final StrategyCandleHistoryService rolling = mock(StrategyCandleHistoryService.class);
    private final SharekhanHistoricalService sharekhan = mock(SharekhanHistoricalService.class);
    private final MarketHistoryArchiveService archive = mock(MarketHistoryArchiveService.class);
    private final NseMarketCalendar calendar = mock(NseMarketCalendar.class);
    private final DailyIndexHistoryScheduler scheduler = new DailyIndexHistoryScheduler(support, rolling, sharekhan, archive, calendar);

    @Test
    void capturesBothIndicesWithoutSubscriptionsAndOnlyWritesTheRunDate() {
        LocalDate date = LocalDate.of(2026, 10, 6);
        LocalDateTime now = date.atTime(19, 0);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        when(calendar.isTradingDay(date)).thenReturn(true);
        StrategyCandle today = new StrategyCandle(date, LocalTime.of(9, 15), 100, 102, 98, 101, null);
        StrategyCandle yesterday = new StrategyCandle(date.minusDays(1), LocalTime.of(9, 15), 100, 102, 98, 101, null);
        for (String symbol : List.of("NIFTY", "BANKNIFTY")) {
            int code = symbol.equals("NIFTY") ? 20000 : 26009;
            ScriptMasterEntity script = ScriptMasterEntity.builder().scripCode(code).exchange("NC").tradingSymbol(symbol).build();
            when(support.resolveSpotScript(symbol)).thenReturn(script);
            when(sharekhan.getRecentHistoricalCandles(code, "5minute")).thenReturn(List.of(
                    new SharekhanHistoricalService.HistoricalCandle(date.minusDays(1), LocalTime.of(9, 15), 100, 102, 98, 101),
                    new SharekhanHistoricalService.HistoricalCandle(date, LocalTime.of(9, 15), 100, 102, 98, 101)));
            when(rolling.mergeAndSave(eq("NC:" + code), anyList(), eq(now))).thenReturn(List.of(yesterday, today));
            when(support.loadCandles(script)).thenReturn(new CandleLoad(List.of(yesterday, today), false, null));
            when(archive.read(symbol, date, date)).thenReturn(List.of());
        }
        scheduler.capture(now);
        for (String symbol : List.of("NIFTY", "BANKNIFTY")) {
            verify(archive).save(symbol, List.of(today), "SHAREKHAN", now);
            verify(archive).save(symbol, List.of(today), "LIVE_CACHE", now);
            verify(archive).save(symbol, List.of(today), "MSTOCK", now);
            verify(archive).read(symbol, date, date);
        }
        verifyNoMoreInteractions(archive);
    }

    @Test
    void oneIndexFailureDoesNotPreventTheOtherIndexFromBeingCaptured() {
        LocalDateTime now = LocalDate.of(2026, 10, 6).atTime(19, 0);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        when(calendar.isTradingDay(now.toLocalDate())).thenReturn(true);
        when(support.resolveSpotScript("NIFTY")).thenThrow(new IllegalArgumentException("missing script"));
        ScriptMasterEntity bank = ScriptMasterEntity.builder().scripCode(26009).exchange("NC").build();
        when(support.resolveSpotScript("BANKNIFTY")).thenReturn(bank);
        when(support.loadCandles(bank)).thenReturn(new CandleLoad(List.of(), false, "no data"));
        scheduler.capture(now);
        verify(archive).read("BANKNIFTY", now.toLocalDate(), now.toLocalDate());
    }

    @Test
    void skipsDisabledJobsAndNonTradingDays() {
        LocalDateTime now = LocalDate.of(2026, 10, 10).atTime(19, 0);
        scheduler.capture(now);
        verifyNoInteractions(calendar, support, archive);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.capture(now);
        verifyNoInteractions(support, archive, sharekhan, rolling);
    }
}
