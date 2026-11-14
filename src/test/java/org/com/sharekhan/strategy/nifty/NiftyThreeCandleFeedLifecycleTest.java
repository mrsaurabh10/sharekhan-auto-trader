package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.ws.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NiftyThreeCandleFeedLifecycleTest {
    final StrategySubscriptionRepository subs=mock(StrategySubscriptionRepository.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final StrategySupport support=mock(StrategySupport.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final TestConnector connector=new TestConnector();
    final WebSocketSubscriptionHelper feeds=new WebSocketSubscriptionHelper(connector);
    final NiftyThreeCandleFeedLifecycle lifecycle=new NiftyThreeCandleFeedLifecycle(subs,trades,support,scripts,feeds);
    final StrategySubscriptionEntity sub=StrategySubscriptionEntity.builder().id(188L).templateId(NiftyThreeCandleRules.TEMPLATE).status("ACTIVE").build();
    final ScriptMasterEntity spot=ScriptMasterEntity.builder().scripCode(20000).exchange("NC").tradingSymbol("NIFTY").build();
    @BeforeEach void setup(){
        when(subs.findByStatusInOrderByIdDesc(anyList())).thenReturn(List.of(sub));when(trades.findBySource(anyString())).thenReturn(List.of());
        when(support.resolveSpotScript("NIFTY")).thenReturn(spot);when(scripts.findByScripCode(20000)).thenReturn(spot);
    }
    @Test void startupAndRepeatedRefreshHoldOneLeaseWithoutEvaluatingSignalsOrOrders(){
        lifecycle.started();lifecycle.refresh();lifecycle.changed(new StrategyFeedChanged(NiftyThreeCandleRules.TEMPLATE));
        assertEquals(1,connector.frames.size());assertEquals(List.of("NIFTY_3C_SUB:188"),feeds.ltpFeedStatus("NC20000").get("owners"));
        verifyNoMoreInteractions(scripts);
    }
    @Test void pauseKeepsPositionDemandAndFinalClosureReleasesOnlyItsOwnFeed(){
        lifecycle.started();var position=TriggeredTradeSetupEntity.builder().id(22L).spotScripCode(20000).status(TriggeredTradeStatus.EXECUTED).build();
        sub.setStatus("PAUSED");when(trades.findBySource(anyString())).thenReturn(List.of(position));lifecycle.refresh();
        assertEquals(1,connector.frames.size());assertEquals(List.of("NIFTY_3C_TRADE:22"),feeds.ltpFeedStatus("NC20000").get("owners"));
        feeds.subscribeToScripLtp("NC20000");position.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);lifecycle.refresh();
        assertTrue(feeds.getActiveScripKeys().contains("NC20000"));assertEquals(1,connector.frames.size());
        feeds.unsubscribeFromScripLtp("NC20000");assertFalse(feeds.getActiveScripKeys().contains("NC20000"));assertEquals(2,connector.frames.size());
    }
    @Test void cancelledFlatSubscriptionReleasesAndResumeRetainsAgain(){
        lifecycle.started();sub.setStatus("CANCELLED");lifecycle.refresh();assertEquals(2,connector.frames.size());
        sub.setStatus("ACTIVE");lifecycle.refresh();assertEquals(3,connector.frames.size());
    }
    @Test void databaseFailureDoesNotDropExistingFeedDemand(){
        lifecycle.started();when(trades.findBySource(anyString())).thenThrow(new IllegalStateException("read unavailable"));
        lifecycle.refresh();assertEquals(1,connector.frames.size());assertTrue(feeds.getActiveScripKeys().contains("NC20000"));
    }
    @Test void existingSchedulerReconcilesBeforeItsEntryTimeGate(){
        var manager=mock(NiftyThreeCandleFeedLifecycle.class);
        var scheduler=new NiftyThreeCandleScheduler(subs,trades,mock(org.com.sharekhan.service.StrategySubscriptionService.class),mock(NiftyThreeCandlePaperBroker.class));
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"feedLifecycle",manager);
        scheduler.poll(java.time.LocalDateTime.of(2026,11,14,8,0));verify(manager).refresh();
    }
    static class TestConnector extends WebSocketConnector {
        final List<String> frames=new ArrayList<>();
        @Override public boolean isConnected(){return true;}
        @Override public boolean trySend(String message){frames.add(message);return true;}
    }
}
