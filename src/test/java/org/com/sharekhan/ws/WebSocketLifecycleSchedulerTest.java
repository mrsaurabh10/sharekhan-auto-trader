package org.com.sharekhan.ws;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasSession;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
class WebSocketLifecycleSchedulerTest {
    @Test void activeNaturalGasConnectsBeforeSessionOpens(){
        var ws=mock(WebSocketClientService.class);var subs=mock(StrategySubscriptionRepository.class);
        when(subs.findByStatusInOrderByIdDesc(anyList())).thenReturn(List.of(StrategySubscriptionEntity.builder().templateId(NaturalGasSession.TEMPLATE).build()));
        new WebSocketLifecycleScheduler(ws,subs).manage(LocalDateTime.of(2026,10,26,8,55));verify(ws).connect();
    }
    @Test void mcxPositionDemandConnectsAtOpenEvenWithPausedEntrySubscriptions(){
        var ws=mock(WebSocketClientService.class);var subs=mock(StrategySubscriptionRepository.class);
        var feeds=mock(WebSocketSubscriptionService.class);when(feeds.getActiveScripKeys()).thenReturn(Set.of("MX583837"));
        var scheduler=new WebSocketLifecycleScheduler(ws,subs);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"feedDemand",feeds);
        scheduler.manage(LocalDateTime.of(2026,11,19,9,0));verify(ws).connect();
    }
    @Test void equityOnlyRetainsExistingConnectionWindow(){
        var ws=mock(WebSocketClientService.class);var subs=mock(StrategySubscriptionRepository.class);
        new WebSocketLifecycleScheduler(ws,subs).manage(LocalDateTime.of(2026,10,26,9,0));verify(ws,never()).connect();
        new WebSocketLifecycleScheduler(ws,subs).manage(LocalDateTime.of(2026,10,26,9,10));verify(ws).connect();
    }
}
