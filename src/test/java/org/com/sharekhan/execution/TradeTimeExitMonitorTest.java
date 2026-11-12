package org.com.sharekhan.execution;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TradeTimeExitMonitorTest {
    @Test void monitorsAnyBrokerWithoutRunAndContinuesOtherLegsWhenAnExitFails() {
        var repo=mock(TriggeredTradeSetupRepository.class);var timer=mock(TradeTimeExitService.class);var execution=mock(TradeExecutionService.class);
        when(repo.findByExecutionSettings_TimeExitMinutesIsNotNullAndStatusIn(anyList())).thenReturn(List.of(
            TriggeredTradeSetupEntity.builder().id(1L).source("manual").build(),
            TriggeredTradeSetupEntity.builder().id(2L).source("any-template").build(),
            TriggeredTradeSetupEntity.builder().id(3L).build()));
        when(timer.observeFromCache(eq(1L),any())).thenReturn(true);when(timer.observeFromCache(eq(2L),any())).thenReturn(true);
        doThrow(new IllegalStateException("No fresh book")).when(execution).squareOffTimeExitTrade(1L);
        new TradeTimeExitMonitor(repo,timer,execution).poll();
        verify(execution).squareOffTimeExitTrade(1L);verify(execution).squareOffTimeExitTrade(2L);verify(execution,never()).squareOffTimeExitTrade(3L);
    }
}
