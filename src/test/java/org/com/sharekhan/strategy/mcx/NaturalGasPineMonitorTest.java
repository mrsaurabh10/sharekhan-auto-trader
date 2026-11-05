package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasPineMonitorTest {
    final StrategyRunRepository runs=mock(StrategyRunRepository.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final NaturalGasPinePolicy policy=new NaturalGasPinePolicy();
    final NaturalGasPineMonitor monitor=new NaturalGasPineMonitor(runs,trades,scripts,brokers,data,quotes,new NaturalGasDataPolicy(NaturalGasDataMode.FULL_SESSION),policy);
    final LocalDateTime now=NaturalGasPineSignalsTest.NOW;
    final StrategyRun run=StrategyRun.builder().id("run").templateId(NaturalGasPineStrategy.TEMPLATE).status("OPEN").appUserId(1L)
            .brokerCredentialsId(2L).tradingDay(now.toLocalDate()).build();
    final TriggeredTradeSetupEntity trade=TriggeredTradeSetupEntity.builder().id(3L).source(NaturalGasPineStrategy.SOURCE)
            .strategyRunId("run").appUserId(1L).brokerCredentialsId(2L).scripCode(22).spotScripCode(11).optionType("CE")
            .entryAt(now.minusMinutes(10)).actualEntryPrice(20d).stopLoss(297d).target1(306d).status(TriggeredTradeStatus.EXECUTED).build();
    @BeforeEach void setup() {
        when(runs.findByTemplateIdAndStatusIn(anyString(),anyList())).thenReturn(List.of(run));
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").build()));
        when(trades.findByStrategyRunId("run")).thenReturn(List.of(trade));
    }
    void price(double value,int age) {when(quotes.getSnapshot(11)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().lastTradedPrice(value)
            .lastLtpAt(now.atZone(ZoneId.of("Asia/Kolkata")).toInstant().minusSeconds(age)).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:11").build()));}
    @Test void ceStopAndTargetAndPeStopAndTargetUseFuturesDirection() {
        assertEquals("MCX_PINE_FUTURES_STOP",NaturalGasPineMonitor.priceExit(trade,297));
        assertEquals("MCX_PINE_FUTURES_TARGET",NaturalGasPineMonitor.priceExit(trade,306));
        assertNull(NaturalGasPineMonitor.priceExit(trade,300));
        trade.setOptionType("PE");trade.setStopLoss(303d);trade.setTarget1(294d);
        assertEquals("MCX_PINE_FUTURES_STOP",NaturalGasPineMonitor.priceExit(trade,303));
        assertEquals("MCX_PINE_FUTURES_TARGET",NaturalGasPineMonitor.priceExit(trade,294));
        assertNull(NaturalGasPineMonitor.priceExit(trade,300));
    }
    @Test void latchesTargetEvenWhenOptionBookMissingOrOptionIsAtALoss() {
        price(306,0);monitor.monitor(now);
        verify(runs).requestClose("run","MCX_PINE_FUTURES_TARGET",now);
        verify(quotes,never()).getSnapshot(22);verifyNoInteractions(data);
    }
    @Test void staleFutureCannotTriggerPriceExit() {
        price(290,16);monitor.monitor(now);verify(runs,never()).requestClose(anyString(),anyString(),any());
    }
    @Test void newsAndTimeExitsDoNotRequireFreshMarketData() {
        var eia=LocalDateTime.of(2026,11,5,20,50);run.setTradingDay(eia.toLocalDate());monitor.monitor(eia);
        verify(runs).requestClose("run","MCX_PINE_NEWS_BLACKOUT",eia);verifyNoInteractions(quotes,data);
        monitor.monitor(eia.withHour(23).withMinute(25));verify(runs).requestClose("run","MCX_PINE_SESSION_CLOSE",eia.withHour(23).withMinute(25));
    }
    @Test void foreignMemberCannotCloseTheRun() {
        trade.setAppUserId(99L);price(290,0);monitor.monitor(now);
        verify(runs,never()).requestClose(anyString(),anyString(),any());verify(runs).recordMessage(eq("run"),anyString());
    }
    @Test void pausedSubscriptionStillHasExitMonitoring() {
        // The monitor deliberately has no subscription-status dependency.
        price(297,0);monitor.monitor(now);verify(runs).requestClose("run","MCX_PINE_FUTURES_STOP",now);
    }
    @Test void staleUnfilledEntryIsCancelledThroughDurableRunClosure() {
        run.setCreatedAt(now.minusSeconds(76));trade.setActualEntryPrice(null);trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        monitor.monitor(now);verify(runs).requestClose("run","MCX_PINE_ENTRY_EXPIRED",now);verifyNoInteractions(quotes,data);
    }
}
