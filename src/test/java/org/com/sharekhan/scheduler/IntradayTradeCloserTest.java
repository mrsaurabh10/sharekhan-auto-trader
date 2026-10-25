package org.com.sharekhan.scheduler;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntradayTradeCloserTest {

    @Test
    void startsClosureChaseForEveryTargetLegEvenWithoutCachedLtp() {
        var setups = mock(TriggeredTradeSetupRepository.class);
        var execution = mock(TradeExecutionService.class);
        var first = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(10265L).scripCode(123).build();
        var second = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(10282L).scripCode(123).build();
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED))
                .thenReturn(java.util.List.of());
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.TARGET_ORDER_PLACED))
                .thenReturn(java.util.List.of(first, second));
        new IntradayTradeCloser(setups, mock(TriggerTradeRequestRepository.class), execution,
                mock(LtpCacheService.class)).closeIntradayTrades();
        verify(execution).startIntradayExitChase(first);
        verify(execution).startIntradayExitChase(second);
    }

    @Test
    void purgeUsesMarketCloseCutoffAndPreservesOnlyNonIntradayPendingConfirmations() {
        TriggerTradeRequestRepository requestRepository = mock(TriggerTradeRequestRepository.class);
        when(requestRepository.deleteStaleRequestsCreatedBeforePreservingNonIntradayStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);
        IntradayTradeCloser scheduler = new IntradayTradeCloser(
                mock(TriggeredTradeSetupRepository.class),
                requestRepository,
                mock(TradeExecutionService.class),
                mock(LtpCacheService.class));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        scheduler.purgeTodayIntradayTradeRequests();

        verify(requestRepository).deleteStaleRequestsCreatedBeforePreservingNonIntradayStatus(
                eq(today.atTime(15, 30)), eq(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION));
    }
    @Test
    void cashCloserHandlesCashOnlyAndPreservesDeliveryOptionsAndBtp() {
        var setups = mock(TriggeredTradeSetupRepository.class);
        var execution = mock(TradeExecutionService.class);
        var ltp = mock(LtpCacheService.class);
        var cash = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(1L).exchange("NC").scripCode(123).build();
        var option = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(2L).exchange("NF").build();
        var btp = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(3L).exchange("NC").brokerProductType("BIGTRADEPLUS").build();
        var target = org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(4L).exchange("BC").build();
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED))
                .thenReturn(java.util.List.of(cash, option, btp));
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.TARGET_ORDER_PLACED))
                .thenReturn(java.util.List.of(target, option));
        when(ltp.getLtp(123)).thenReturn(750d);
        new IntradayTradeCloser(setups, mock(TriggerTradeRequestRepository.class), execution, ltp).closeCashIntradayTrades();
        verify(execution).squareOff(cash, 750d, "INTRADAY_CLOSE");
        verify(execution).startIntradayExitChase(target);
        org.mockito.Mockito.verifyNoMoreInteractions(execution);
    }


    @Test void equityCloserLeavesNaturalGasForItsOwnSession() {
        var setups=mock(TriggeredTradeSetupRepository.class);var execution=mock(TradeExecutionService.class);
        var ng=org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().id(99L).scripCode(42)
                .source(org.com.sharekhan.strategy.mcx.NaturalGasSession.SOURCE).build();
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED)).thenReturn(java.util.List.of(ng));
        when(setups.findByIntradayTrueAndStatus(org.com.sharekhan.enums.TriggeredTradeStatus.TARGET_ORDER_PLACED)).thenReturn(java.util.List.of(ng));
        new IntradayTradeCloser(setups,mock(TriggerTradeRequestRepository.class),execution,mock(LtpCacheService.class)).closeIntradayTrades();
        verify(execution,org.mockito.Mockito.never()).squareOff(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyString());
        verify(execution,org.mockito.Mockito.never()).startIntradayExitChase(org.mockito.ArgumentMatchers.any());
    }
}
