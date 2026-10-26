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
    final NaturalGasMarketData data=new NaturalGasMarketData(history,live);
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
}
