package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTriggerServiceTest {

    private final TriggerTradeRequestRepository triggerRepo = mock(TriggerTradeRequestRepository.class);
    private final TriggeredTradeSetupRepository triggeredRepo = mock(TriggeredTradeSetupRepository.class);
    private final TradeExecutionService tradeExecutionService = mock(TradeExecutionService.class);
    private final LtpCacheService ltpCacheService = mock(LtpCacheService.class);
    private final SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);

    private final PriceTriggerService service = new PriceTriggerService(
            triggerRepo,
            triggeredRepo,
            tradeExecutionService,
            new NoopTransactionManager(),
            mock(ScriptMasterRepository.class),
            mock(WebSocketSubscriptionService.class),
            ltpCacheService,
            mock(MStockLtpService.class),
            mock(MStockInstrumentResolver.class),
            historicalService,
            mock(ScripExecutorManager.class)
    );

    @Test
    void ruleBTriggersAfterDirectionalMinuteCloseEvenIfNextTickFallsBackBelowEntry() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7001L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 20), 99.0, 105.0, 98.0, 101.0));
        when(triggerRepo.claimIfStatusEquals(7001L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.TRIGGERED.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 99.0);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void ruleBResetsAfterStopCloseAndKeepsRequestPendingForRetrigger() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7002L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 20), 95.0, 99.0, 88.0, 89.0));

        timedService.evaluatePriceTrigger(20000, 100.0);

        verify(triggerRepo).save(trigger);
        org.assertj.core.api.Assertions.assertThat(trigger.getOpeningRuleReset()).isTrue();
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void ruleBRejectsTargetTouchAfterRequestWithinTheSameMinute() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7008L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 20), 99.0, 110.0, 98.0, 99.0));
        when(ltpCacheService.hasPriceTouchedSince(20000, trigger.getCreatedAt(), 110.0, false)).thenReturn(true);
        when(triggerRepo.claimIfStatusEquals(7008L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name()))
                .thenReturn(1);

        timedService.evaluatePriceTrigger(20000, 99.0);

        verify(triggerRepo).claimIfStatusEquals(7008L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void ruleBReturnsToNormalEntryMonitoringAt0930WithoutACompletedConfirmation() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 30)).when(timedService).nowIst();
        var trigger = atrTrigger(7005L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(triggerRepo.claimIfStatusEquals(7005L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.TRIGGERED.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 100.0);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void initializesGapUpProtectionWithPreviousCloseAsTighterCloseStop() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 31)).when(timedService).nowIst();
        var trigger = atrTrigger(7003L, 100.0, 95.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(historicalService.getTodayMarketOpenPrice(20000)).thenReturn(OptionalDouble.of(100.5));
        when(historicalService.getPreviousTradingClose(20000)).thenReturn(OptionalDouble.of(98.0));

        timedService.evaluatePriceTrigger(20000, 99.0);

        org.assertj.core.api.Assertions.assertThat(trigger.getGapProtectionEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(trigger.getGapStopLoss()).isEqualTo(98.0);
        verify(triggerRepo).save(trigger);
    }

    @Test
    void initializesMirroredGapDownProtectionForPe() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 31)).when(timedService).nowIst();
        var trigger = atrTrigger(7006L, 100.0, 105.0, 90.0);
        trigger.setOptionType("PE");

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(historicalService.getTodayMarketOpenPrice(20000)).thenReturn(OptionalDouble.of(99.5));
        when(historicalService.getPreviousTradingClose(20000)).thenReturn(OptionalDouble.of(102.0));

        timedService.evaluatePriceTrigger(20000, 101.0);

        org.assertj.core.api.Assertions.assertThat(trigger.getGapProtectionEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(trigger.getGapStopLoss()).isEqualTo(102.0);
        verify(triggerRepo).save(trigger);
    }

    @Test
    void doesNotClassifyGapFromCapturedTickWhenExactMarketOpenIsUnavailable() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 31)).when(timedService).nowIst();
        var trigger = atrTrigger(7009L, 100.0, 95.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(ltpCacheService.getTodayOpeningPrice(20000)).thenReturn(102.0);
        when(historicalService.getTodayMarketOpenPrice(20000)).thenReturn(OptionalDouble.empty());
        when(historicalService.getPreviousTradingClose(20000)).thenReturn(OptionalDouble.of(98.0));

        timedService.evaluatePriceTrigger(20000, 99.0);

        org.assertj.core.api.Assertions.assertThat(trigger.getGapPolicyInitialized()).isNull();
        org.assertj.core.api.Assertions.assertThat(trigger.getGapProtectionEnabled()).isNull();
        verify(triggerRepo, never()).save(trigger);
    }

    @Test
    void gapStopUsesCompletedSpotCloseAndExitsWithGapReason() {
        TriggeredTradeSetupEntity trade = optionTrade(7004L, 999999, 20000);
        trade.setOptionType("CE");
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setUseSpotForSl(true);
        trade.setUseSpotForTarget(true);
        trade.setEntryAt(LocalDateTime.of(2026, 7, 3, 9, 25));
        trade.setStopLoss(95.0);
        trade.setTarget1(110.0);
        trade.setGapProtectionEnabled(true);
        trade.setGapStopLoss(98.0);
        trade.setQuantity(100L);
        trade.setActualEntryPrice(50.0);
        trade.setOrderId("REAL-ENTRY");

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(48.0);
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 36), 99.0, 100.0, 96.0, 97.0));
        when(triggeredRepo.findById(7004L)).thenReturn(Optional.of(trade));
        when(tradeExecutionService.hasUsableTradedExitPrice(trade, 48.0)).thenReturn(true);
        when(triggeredRepo.claimIfStatusEquals(7004L, TriggeredTradeStatus.EXECUTED.name(),
                TriggeredTradeStatus.EXIT_TRIGGERED.name(), "GAP_FILL_STOP")).thenAnswer(invocation -> {
                    trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
                    trade.setExitReason("GAP_FILL_STOP");
                    return 1;
                });

        service.monitorOpenTrades(20000, 97.0);

        verify(tradeExecutionService).squareOff(trade, 48.0, "GAP_FILL_STOP");
    }

    @Test
    void spotStopIgnoresIntraminuteTouchUntilCompletedCandleClosesBeyondSl() {
        TriggeredTradeSetupEntity trade = optionTrade(7007L, 999999, 20000);
        trade.setOptionType("CE");
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setUseSpotForSl(true);
        trade.setUseSpotForTarget(true);
        trade.setEntryAt(LocalDateTime.of(2026, 7, 3, 9, 25));
        trade.setStopLoss(95.0);
        trade.setTarget1(110.0);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(48.0);
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 36), 100.0, 101.0, 94.0, 100.0));
        when(triggeredRepo.findById(7007L)).thenReturn(Optional.of(trade));

        service.monitorOpenTrades(20000, 94.0);

        verify(triggeredRepo, never()).claimIfStatusEquals(eq(7007L), anyString(), anyString(), anyString());
        verify(tradeExecutionService, never()).squareOff(any(), anyDouble(), anyString());
    }

    @Test
    void monitorOpenTradesDoesNotUseSpotTickAsTradedPriceWhenOptionScripMatchesSpot() {
        TriggeredTradeSetupEntity trade = optionTrade(5209L, 20000, 20000);
        trade.setTarget1(131.2);
        trade.setExitOrderId("183759611");

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));

        service.monitorOpenTrades(20000, 23357.0);

        verify(tradeExecutionService, never()).hasUsableTradedExitPrice(any(), anyDouble());
        verify(tradeExecutionService, never()).modifyExitOrderForTarget(any(), anyDouble());
        verify(tradeExecutionService, never()).squareOff(any(), anyDouble(), anyString());
        verify(triggeredRepo, never()).claimIfStatusEquals(any(), anyString(), anyString(), anyString());
    }

    @Test
    void monitorOpenTradesUsesCachedOptionLtpWhenSpotTickTriggersSpotTarget() {
        TriggeredTradeSetupEntity trade = optionTrade(5210L, 999999, 20000);
        trade.setUseSpotForTarget(true);
        trade.setTarget1(23400.0);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(120.0);
        when(triggeredRepo.findById(5210L)).thenReturn(Optional.of(trade));
        when(tradeExecutionService.hasUsableTradedExitPrice(trade, 120.0)).thenReturn(true);
        when(triggeredRepo.claimIfStatusEquals(eq(5210L), anyString(), anyString(), anyString())).thenReturn(0);

        service.monitorOpenTrades(20000, 23357.0);

        verify(tradeExecutionService).hasUsableTradedExitPrice(trade, 120.0);
        verify(tradeExecutionService, never()).hasUsableTradedExitPrice(trade, 23357.0);
        verify(tradeExecutionService, never()).modifyExitOrderForTarget(any(), eq(23357.0));
        verify(tradeExecutionService, never()).squareOff(any(), eq(23357.0), anyString());
    }

    private TriggeredTradeSetupEntity optionTrade(Long id, Integer scripCode, Integer spotScripCode) {
        return TriggeredTradeSetupEntity.builder()
                .id(id)
                .symbol("NIFTY")
                .scripCode(scripCode)
                .spotScripCode(spotScripCode)
                .optionType("PE")
                .entryPrice(110.0)
                .actualEntryPrice(111.2)
                .status(TriggeredTradeStatus.EXECUTED)
                .useSpotForEntry(false)
                .useSpotForSl(false)
                .useSpotForTarget(false)
                .build();
    }

    private org.com.sharekhan.entity.TriggerTradeRequestEntity atrTrigger(Long id,
                                                                          double entry,
                                                                          double stop,
                                                                          double target) {
        return org.com.sharekhan.entity.TriggerTradeRequestEntity.builder()
                .id(id)
                .symbol("TEST")
                .scripCode(999999)
                .spotScripCode(20000)
                .optionType("CE")
                .entryPrice(entry)
                .stopLoss(stop)
                .target1(target)
                .source("atr-signal")
                .useSpotForEntry(true)
                .createdAt(LocalDateTime.of(2026, 7, 3, 9, 20, 10))
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .build();
    }

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
