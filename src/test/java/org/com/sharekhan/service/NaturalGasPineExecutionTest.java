package org.com.sharekhan.service;

import java.time.*;
import java.util.*;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.strategy.mcx.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaturalGasPineExecutionTest {
    TriggeredTradeSetupEntity trade(String source) {return TriggeredTradeSetupEntity.builder().id(1L).source(source)
            .entryPrice(20d).stopLoss(297d).target1(306d).useSpotForSl(true).useSpotForTarget(true).build();}
    @Test void immediateAndPolledOptionFillsNeverShiftAbsoluteFuturesLevels() {
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);
        for(String method:List.of("applyImmediateEntryExecution","applyConfirmedEntryPrice")) {
            var trade=trade(NaturalGasPineStrategy.SOURCE);
            if(method.equals("applyImmediateEntryExecution"))ReflectionTestUtils.invokeMethod(service,method,trade,OrderPlacementResult.builder().executedPrice(20.2).build());
            else ReflectionTestUtils.invokeMethod(service,method,trade,20.2d);
            assertEquals(297,trade.getStopLoss());assertEquals(306,trade.getTarget1());assertEquals(20.2,trade.getActualEntryPrice());
        }
    }
    @Test void existingPremiumStrategyKeepsItsFillAdjustmentBehavior() {
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);var trade=trade(NaturalGasSession.SOURCE);
        ReflectionTestUtils.invokeMethod(service,"applyConfirmedEntryPrice",trade,20.2d);
        assertEquals(297.2,trade.getStopLoss(),1e-9);assertEquals(306.2,trade.getTarget1(),1e-9);
    }
    @Test void genericCandleAndPremiumExitRulesCannotOverridePineMonitor() {
        var repo=mock(TriggeredTradeSetupRepository.class);when(repo.findById(1L)).thenReturn(Optional.of(trade(NaturalGasPineStrategy.SOURCE)));
        var service=mock(PriceTriggerService.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(service,"triggeredRepo",repo);
        assertDoesNotThrow(()->service.handleTradeWithLock(1L,20,296d));verify(repo).findById(1L);verifyNoMoreInteractions(repo);
    }
    @Test void executionGateRechecksDstBlackoutAndLateEntryWindow() {
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(service,"ngPinePolicy",new NaturalGasPinePolicy());
        for(String time:List.of("2026-11-05T21:00:00","2026-11-05T23:15:00","2026-11-05T18:29:59")) {
            ReflectionTestUtils.setField(service,"entryClock",Clock.fixed(LocalDateTime.parse(time).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),ZoneId.of("Asia/Kolkata")));
            assertFalse(service.isIntradayEntryWindowOpen(NaturalGasPineStrategy.SOURCE));
        }
        ReflectionTestUtils.setField(service,"entryClock",Clock.fixed(LocalDateTime.parse("2026-11-05T23:10:00").atZone(ZoneId.of("Asia/Kolkata")).toInstant(),ZoneId.of("Asia/Kolkata")));
        assertTrue(service.isIntradayEntryWindowOpen(NaturalGasPineStrategy.SOURCE));
        assertFalse(service.isIntradayEntryWindowOpen(NaturalGasSession.SOURCE));
    }
    @Test void futuresTargetExitUsesOptionBidEvenBelowOptionEntry() {
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);
        var repo=mock(TriggeredTradeSetupRepository.class);var quotes=mock(QuoteCacheService.class);
        var trade=trade(NaturalGasPineStrategy.SOURCE);trade.setStrategyRunId("run");trade.setScripCode(22);trade.setActualEntryPrice(20d);
        when(repo.findById(1L)).thenReturn(Optional.of(trade));
        when(quotes.getSnapshot(22)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().bestBid(10d).bestAsk(10.1)
                .coherentBook(true).lastBookAt(Instant.now()).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:22").build()));
        ReflectionTestUtils.setField(service,"triggeredTradeRepo",repo);ReflectionTestUtils.setField(service,"quoteCacheService",quotes);
        doNothing().when(service).squareOff(any(),anyDouble(),anyString());
        service.squareOffStrategyRunTrade(1L,"run","MCX_PINE_FUTURES_TARGET");
        verify(service).squareOff(trade,10d,"MCX_PINE_FUTURES_TARGET");
    }
    @Test void missingOptionBookDoesNotSubstituteFuturesOrCachedLtpForExit() {
        var service=mock(TradeExecutionService.class,CALLS_REAL_METHODS);
        var repo=mock(TriggeredTradeSetupRepository.class);var quotes=mock(QuoteCacheService.class);
        var trade=trade(NaturalGasPineStrategy.SOURCE);trade.setStrategyRunId("run");trade.setScripCode(22);
        when(repo.findById(1L)).thenReturn(Optional.of(trade));
        ReflectionTestUtils.setField(service,"triggeredTradeRepo",repo);ReflectionTestUtils.setField(service,"quoteCacheService",quotes);
        assertThrows(IllegalStateException.class,()->service.squareOffStrategyRunTrade(1L,"run","MCX_PINE_FUTURES_STOP"));
        verify(service,never()).squareOff(any(),anyDouble(),anyString());
    }
}
