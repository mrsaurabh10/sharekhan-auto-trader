package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaturalGasMarketDataTest {
    final SharekhanHistoricalService history=mock(SharekhanHistoricalService.class);
    final NaturalGasLiveCandles live=mock(NaturalGasLiveCandles.class);
    final NaturalGasMarketData data=new NaturalGasMarketData(history,live,mock(NaturalGasShoonyaCandles.class));
    final LocalDateTime now=LocalDateTime.of(2026,10,26,11,5);
    @Test void normalizesActualBrokerLastTradeStampsWithinTheirIntervalAndDropsClosingStub() {
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of(
                new SharekhanHistoricalService.HistoricalCandle(now.toLocalDate().minusDays(3),LocalTime.of(9,4,57),300,301,299,300,1243L),
                new SharekhanHistoricalService.HistoricalCandle(now.toLocalDate().minusDays(3),LocalTime.of(23,30,59),300,301,299,300,0L)));
        var rows=data.candles(42,now);assertEquals(1,rows.size());assertEquals(LocalTime.of(9,0),rows.getFirst().time());assertEquals(1243L,rows.getFirst().volume());
    }
    @Test void completedLiveBarIsNotHeldBackByHistoricalResponseCache() {
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of());
        assertTrue(data.candles(42,now).isEmpty());
        var bar=new StrategyCandle(now.toLocalDate(),LocalTime.of(11,5),300,301,299,300,200L);
        when(live.completed(42,now.plusMinutes(5))).thenReturn(List.of(bar));
        assertEquals(List.of(bar),data.candles(42,now.plusMinutes(5)));
    }
    @Test void observedCandleWinsWithoutDuplicatingMatchingHistory() {
        var bar=new StrategyCandle(now.toLocalDate(),LocalTime.of(11,0),300,301,299,300,200L);
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of(new SharekhanHistoricalService.HistoricalCandle(bar.date(),LocalTime.of(11,4,59),300,301,299,300,100L)));
        when(live.completed(42,now)).thenReturn(List.of(bar));assertEquals(List.of(bar),data.candles(42,now));
    }
    @Test void exactBoundaryAndFollowingLastTradeProduceSeparateVerifiedIntervals() {
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of(
            new SharekhanHistoricalService.HistoricalCandle(LocalDate.of(2026,10,19),LocalTime.of(13,0),324.5,324.7,324.5,324.7,65L),
            new SharekhanHistoricalService.HistoricalCandle(LocalDate.of(2026,10,19),LocalTime.of(13,4,56),324.6,324.7,324.4,324.4,38L)));
        var rows=data.candles(42,now);
        assertEquals(List.of(LocalTime.of(12,55),LocalTime.of(13,0)),rows.stream().map(StrategyCandle::time).toList());
        assertEquals(List.of(65L,38L),rows.stream().map(StrategyCandle::volume).toList());
    }
    @Test void persistedLateWindowIsImmediatelyUsableAfterRestartWithoutNewWarmup() {
        var rows=NaturalGasSignalsTest.lateCandles(true,LocalTime.of(17,0));
        var repository=mock(org.com.sharekhan.repository.StrategyCandleHistoryRepository.class);
        var saved=rows.stream().filter(c->c.date().equals(NaturalGasSignalsTest.DAY)).map(c->org.com.sharekhan.entity.StrategyCandleHistoryEntity.builder()
            .instrumentKey("SHAREKHAN_MCX_LIVE:42").candleTime(LocalDateTime.of(c.date(),c.time())).openPrice(c.open()).highPrice(c.high()).lowPrice(c.low()).closePrice(c.close()).volume(c.volume()).build()).toList();
        when(repository.findTop1000ByInstrumentKeyOrderByCandleTimeDesc("SHAREKHAN_MCX_LIVE:42")).thenReturn(saved);
        var restarted=new NaturalGasLiveCandles(repository);var reloaded=new NaturalGasMarketData(history,restarted,mock(NaturalGasShoonyaCandles.class));
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(rows.stream().filter(c->!c.date().equals(NaturalGasSignalsTest.DAY))
            .map(c->new SharekhanHistoricalService.HistoricalCandle(c.date(),c.time().plusMinutes(4).plusSeconds(59),c.open(),c.high(),c.low(),c.close(),c.volume())).toList());
        var evening=NaturalGasSignalsTest.DAY.atTime(19,0,5);var candles=reloaded.candles(42,evening);
        assertTrue(NaturalGasSignals.inspect(candles,evening,.1,NaturalGasDataMode.ROLLING_WINDOW).signal().isPresent());
        assertTrue(NaturalGasSignals.inspect(candles,evening,.1).reason().startsWith("Incomplete MCX session"));
        verify(repository,never()).save(any());
    }

    @Test void shoonyaIsAuthoritativeForTodayAndNeverInterleavesTickBarsOnFailure() {
        var future=org.com.sharekhan.entity.ScriptMasterEntity.builder().scripCode(42).build();var provider=mock(NaturalGasShoonyaCandles.class);
        var mixed=new NaturalGasMarketData(history,live,provider);
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of(new SharekhanHistoricalService.HistoricalCandle(now.toLocalDate().minusDays(3),LocalTime.of(9,4,59),300,301,299,300,100L)));
        var tick=new StrategyCandle(now.toLocalDate(),LocalTime.of(9,0),300,302,298,301,999L);when(live.completed(42,now)).thenReturn(List.of(tick));
        var broker=new StrategyCandle(now.toLocalDate(),LocalTime.of(9,0),300,303,297,302,123L);when(provider.completed(future,now)).thenReturn(List.of(broker));
        var rows=mixed.shoonyaCandles(future,now);assertEquals(2,rows.size());assertEquals(broker,rows.getLast());assertFalse(rows.contains(tick));
        when(provider.completed(future,now)).thenReturn(List.of());assertTrue(mixed.shoonyaCandles(future,now).stream().noneMatch(c->c.date().equals(now.toLocalDate())));
        assertTrue(mixed.candles(42,now).contains(tick));
    }
    @Test void emptyPriorRefreshRetainsVerifiedSeedRatherThanErasingIt() {
        when(history.getRecentRawHistoricalCandles(42,"5minute")).thenReturn(List.of(new SharekhanHistoricalService.HistoricalCandle(now.toLocalDate().minusDays(3),LocalTime.of(9,4,59),300,301,299,300,100L)),List.of());
        var first=data.candles(42,now);assertEquals(first,data.candles(42,now.plusMinutes(6)));
    }

}
