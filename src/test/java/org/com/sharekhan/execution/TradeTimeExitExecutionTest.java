package org.com.sharekhan.execution;

import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TradeTimeExitExecutionTest {
    @Test void normalQueuesRetainBrokerPriorityAndTimeoutIntentOnQueueRejection() {
        var repo=mock(TriggeredTradeSetupRepository.class);var brokers=mock(BrokerCredentialsRepository.class);
        var dispatcher=mock(OrderExecutionDispatcher.class);var execution=mock(TradeExecutionService.class);
        var config=new TradeExecutionSettings();config.setTimeExitMinutes(15);
        var t=TriggeredTradeSetupEntity.builder().id(1L).brokerCredentialsId(4L).executionSettings(config).exitReason("TIME_EXIT").build();
        when(repo.findById(1L)).thenReturn(Optional.of(t));
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().brokerName("Simulator").build()));
        var worker=new TradeTimeExitExecution(repo,brokers,dispatcher,execution);
        assertFalse(worker.submit(1L));verify(dispatcher).submit(eq("SIM:EXIT:1"),any());verifyNoInteractions(execution);
        assertEquals("TIME_EXIT",t.getExitReason());
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().brokerName("AnyBroker").build()));
        when(dispatcher.submit(eq("EXIT:1"),any())).thenAnswer(i->{((Runnable)i.getArgument(1)).run();return true;});
        assertTrue(worker.submit(1L));verify(execution).squareOffTimeExitTrade(1L);
    }
}
