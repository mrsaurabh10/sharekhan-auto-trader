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
        var repo=mock(TriggeredTradeSetupRepository.class);var timer=mock(TradeTimeExitService.class);var execution=mock(TradeTimeExitExecution.class);
        when(repo.findByExecutionSettings_TimeExitMinutesIsNotNullAndStatusIn(anyList())).thenReturn(List.of(
            TriggeredTradeSetupEntity.builder().id(1L).source("manual").build(),
            TriggeredTradeSetupEntity.builder().id(2L).source("any-template").build(),
            TriggeredTradeSetupEntity.builder().id(3L).build()));
        when(timer.observeFromCache(1L)).thenReturn(true);when(timer.observeFromCache(2L)).thenReturn(true);
        when(execution.submit(1L)).thenThrow(new IllegalStateException("Queue unavailable"));
        new TradeTimeExitMonitor(repo,timer,execution).poll();
        verify(execution).submit(1L);verify(execution).submit(2L);verify(execution,never()).submit(3L);
    }
}
