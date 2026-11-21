package org.com.sharekhan.service;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
class NaturalGasScalpSessionCloseMonitorTest {
    @Test void unlinkedPositionRecoversWithoutAnyActiveSubscriptionOrRunAndFailureKeepsFeedOwned() {
        var trades=mock(TriggeredTradeSetupRepository.class);var close=mock(NaturalGasScalpSessionCloseService.class);
        var execution=mock(TradeExecutionService.class);var monitor=new NaturalGasScalpSessionCloseMonitor(trades,close,execution);
        var now=LocalDateTime.of(2026,11,19,9,0);
        var t=TriggeredTradeSetupEntity.builder().id(11411L).source(NaturalGasScalpingStrategy.SOURCE).exchange("MX")
                .intraday(true).scripCode(583837).entryAt(now.minusDays(1)).quantity(1250L).status(TriggeredTradeStatus.EXECUTED).build();
        when(trades.findBySourceAndStatusIn(anyString(),anyList())).thenReturn(List.of(t));
        doThrow(new IllegalStateException("missing book")).when(execution).closeNaturalGasScalpSessionTrade(11411L);
        monitor.monitor(now);verify(close).retain(eq(t),any());verify(close).releaseFlat(Set.of(11411L));
        when(trades.findBySourceAndStatusIn(anyString(),anyList())).thenReturn(List.of());
        monitor.monitor(now.plusSeconds(5));verify(close).releaseFlat(Set.of());
    }
}
