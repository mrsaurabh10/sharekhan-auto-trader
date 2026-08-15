package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    private final ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
    private final LtpCacheService ltpCacheService = mock(LtpCacheService.class);
    private final SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
    private final MStockIntradayCandleService intradayCandleService = mock(MStockIntradayCandleService.class);
    private final OrderExecutionDispatcher orderExecutionDispatcher = mock(OrderExecutionDispatcher.class);

    private final PriceTriggerService service = new PriceTriggerService(
            triggerRepo,
            triggeredRepo,
            tradeExecutionService,
            new NoopTransactionManager(),
            scriptMasterRepository,
            mock(WebSocketSubscriptionService.class),
            ltpCacheService,
            mock(MStockLtpService.class),
            intradayCandleService,
            mock(MStockInstrumentResolver.class),
            historicalService,
            mock(ScripExecutorManager.class),
            orderExecutionDispatcher,
            mock(BrokerCredentialsRepository.class)
    );

    {
        // A dispatched entry must atomically move into the non-triggerable broker-submitting state.
        when(triggerRepo.claimIfStatusEquals(anyLong(),
                eq(TriggeredTradeStatus.ENTRY_SUBMITTING.name()),
                anyString())).thenReturn(1);
        when(orderExecutionDispatcher.submit(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
    }

    @Test
    void doesNotEvaluateOrRecoverEntriesOnWeekend() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 18, 10, 0)).when(timedService).nowIst();

        timedService.evaluatePriceTrigger(20000, 100.0);
        timedService.recoverStaleTriggeredRequests();

        verify(triggerRepo, never()).findByScripCodeAndStatus(any(), any());
        verify(triggerRepo, never()).findByStatus(TriggeredTradeStatus.TRIGGERED);
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void doesNotEvaluateOrRecoverEntriesBeforeNineTwenty() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 19, 59)).when(timedService).nowIst();

        timedService.evaluatePriceTrigger(20000, 100.0);
        timedService.recoverStaleTriggeredRequests();

        verify(triggerRepo, never()).findByScripCodeAndStatus(any(), any());
        verify(triggerRepo, never()).findByStatus(TriggeredTradeStatus.TRIGGERED);
    }

    @Test
    void evaluatesAndRecoversEntriesAtNineTwenty() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 20)).when(timedService).nowIst();
        when(triggerRepo.findByScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findByStatus(TriggeredTradeStatus.TRIGGERED)).thenReturn(List.of());

        timedService.evaluatePriceTrigger(20000, 100.0);
        timedService.recoverStaleTriggeredRequests();

        verify(triggerRepo).findByScripCodeAndStatus(20000, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        verify(triggerRepo).findByStatus(TriggeredTradeStatus.TRIGGERED);
    }

    @Test
    void recoveryFailsAnUnknownBrokerSubmissionInsteadOfRearmingIt() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        TriggerTradeRequestEntity request = manualSpotTrigger(7098L, 100.0);
        request.setStatus(TriggeredTradeStatus.ENTRY_SUBMITTING);
        when(triggerRepo.findByStatus(TriggeredTradeStatus.TRIGGERED)).thenReturn(List.of());
        when(triggerRepo.findByStatus(TriggeredTradeStatus.ENTRY_SUBMITTING)).thenReturn(List.of(request));
        when(triggeredRepo.findByTriggerRequestId(7098L)).thenReturn(List.of());
        when(triggerRepo.claimIfStatusEqualsWithOutcome(7098L,
                TriggeredTradeStatus.ENTRY_SUBMITTING.name(), TriggeredTradeStatus.FAILED.name(),
                "ENTRY_SUBMISSION_STATE_UNKNOWN",
                "Recovery found no persisted trade after an incomplete entry submission; retry is blocked because broker submission state cannot be proven."))
                .thenReturn(1);

        timedService.recoverStaleTriggeredRequests();

        verify(triggerRepo).claimIfStatusEqualsWithOutcome(7098L,
                TriggeredTradeStatus.ENTRY_SUBMITTING.name(), TriggeredTradeStatus.FAILED.name(),
                "ENTRY_SUBMISSION_STATE_UNKNOWN",
                "Recovery found no persisted trade after an incomplete entry submission; retry is blocked because broker submission state cannot be proven.");
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void skipsBrokerExecutionWhenAnotherWorkerAlreadyClaimedTriggeredRequest() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        var trigger = manualSpotTrigger(7099L, 100.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(triggerRepo.claimIfStatusEquals(7099L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.TRIGGERED.name())).thenReturn(1);
        when(triggerRepo.claimIfStatusEquals(7099L,
                TriggeredTradeStatus.TRIGGERED.name(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name())).thenReturn(0);

        timedService.evaluatePriceTrigger(20000, 100.1);

        verify(tradeExecutionService, never()).executeTradeFromEntity(trigger);
    }

    @Test
    void atrSpotEntryWaitsWhenCurrentTickFallsBackAfterDirectionalClose() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7001L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 20, 99.0, 105.0, 98.0, 101.0);
        timedService.evaluatePriceTrigger(20000, 99.0);

        verify(triggerRepo, never()).claimIfStatusEquals(7001L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void atrSpotEntryTriggersWhenDirectionalCloseAndCurrentTickRemainValid() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7016L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 20, 99.0, 105.0, 98.0, 101.0);
        when(triggerRepo.claimIfStatusEquals(7016L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 100.2);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void atrSpotPeEntryTriggersWhenCompletedCandleClosesExactlyAtEntry() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7020L, 380.30, 382.32, 378.28);
        trigger.setOptionType("PE");

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 20, 380.50, 380.60, 380.20, 380.30);
        when(triggerRepo.claimIfStatusEquals(7020L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 380.30);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void atrSpotCeEntryTriggersWhenCompletedCandleClosesExactlyAtEntry() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7021L, 380.30, 378.28, 382.32);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 20, 380.10, 380.50, 380.00, 380.30);
        when(triggerRepo.claimIfStatusEquals(7021L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 380.30);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void atrSpotEntryDoesNotDependOnCreatedAt() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 33, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7023L, 100.0, 90.0, 110.0);
        trigger.setCreatedAt(null);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 32, 99.0, 101.0, 98.5, 100.0);
        when(triggerRepo.claimIfStatusEquals(7023L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 100.0);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void atrSpotEntryWaitsWhenCachedCompletedCandleIsStale() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 21, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7022L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 19, 99.0, 101.0, 98.0, 100.50);

        timedService.evaluatePriceTrigger(20000, 100.20);

        verify(triggerRepo, never()).claimIfStatusEquals(7022L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
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
        stubIntradayCandle(9, 20, 95.0, 99.0, 88.0, 89.0);

        timedService.evaluatePriceTrigger(20000, 100.0);

        verify(triggerRepo).save(trigger);
        org.assertj.core.api.Assertions.assertThat(trigger.getOpeningRuleReset()).isTrue();
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void ruleBResetStillRequiresDirectionalMinuteCloseBefore0930() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 22, 12)).when(timedService).nowIst();
        var trigger = atrTrigger(7011L, 100.0, 90.0, 110.0);
        trigger.setOpeningRuleReset(Boolean.TRUE);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 21, 99.0, 100.5, 98.0, 99.5);

        timedService.evaluatePriceTrigger(20000, 100.1);

        verify(triggerRepo, never()).claimIfStatusEquals(7011L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
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
        stubIntradayCandle(9, 20, 99.0, 110.0, 98.0, 99.0);
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
    void atrSpotEntryStillRequiresCompletedConfirmationAt0930() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 30)).when(timedService).nowIst();
        var trigger = atrTrigger(7005L, 100.0, 90.0, 110.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        timedService.evaluatePriceTrigger(20000, 100.0);

        verify(triggerRepo, never()).claimIfStatusEquals(7005L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void atrSpotEntryAfter0930RejectsRawTickWhenCompletedCeCandleClosedBelowEntry() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 33, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7017L, 490.80, 487.76, 493.84);
        trigger.setCreatedAt(LocalDateTime.of(2026, 7, 3, 9, 32, 10));

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 32, 489.70, 490.95, 489.50, 490.25);

        timedService.evaluatePriceTrigger(20000, 490.90);

        verify(triggerRepo, never()).claimIfStatusEquals(7017L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
    }

    @Test
    void atrSpotPeEntryAfter0930RequiresDirectionalCloseAndValidCurrentTick() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 33, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7018L, 100.0, 105.0, 95.0);
        trigger.setOptionType("PE");
        trigger.setCreatedAt(LocalDateTime.of(2026, 7, 3, 9, 32, 10));

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 32, 100.20, 100.30, 99.20, 99.50);
        when(triggerRepo.claimIfStatusEquals(7018L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 99.70);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void nonAtrSpotEntryRetainsTickBasedTriggering() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        var trigger = atrTrigger(7019L, 100.0, 90.0, 110.0);
        trigger.setSource("manual");

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(triggerRepo.claimIfStatusEquals(7019L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 100.10);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
        verify(intradayCandleService, never()).getCompletedMinuteCandle(eq(20000), any());
    }

    @Test
    void atrPreviousDaySpotEntryRejectsWhenTarget1WasAlreadyPassed() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 33, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7024L, 640.25, 643.05, 637.45);
        trigger.setSource("atr-pdh-pdl-strategy");
        trigger.setOptionType("PE");
        trigger.setCreatedAt(LocalDateTime.of(2026, 7, 3, 9, 32, 10));

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 32, 638.0, 638.0, 633.0, 633.0);

        timedService.evaluatePriceTrigger(20000, 633.0);

        verify(triggerRepo).claimIfStatusEquals(7024L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(trigger);
    }

    @Test
    void atrPreviousDaySpotEntryRejectsInsideTenPercentOfTarget1Distance() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 33, 1)).when(timedService).nowIst();
        var trigger = atrTrigger(7025L, 100.0, 95.0, 110.0);
        trigger.setSource("atr-pdh-pdl-strategy");
        trigger.setCreatedAt(LocalDateTime.of(2026, 7, 3, 9, 32, 10));

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        stubIntradayCandle(9, 32, 100.0, 109.0, 100.0, 109.0);

        timedService.evaluatePriceTrigger(20000, 109.0);

        verify(triggerRepo).claimIfStatusEquals(7025L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
        verify(tradeExecutionService, never()).executeTradeFromEntity(trigger);
    }

    @Test
    void dynamicStrategySpotSignalSkipsTheOpeningGapGuard() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        TriggerTradeRequestEntity trigger = manualSpotTrigger(7030L, 100.0);
        trigger.setSource("strategy:FNO_0925_MOVER_ATR_BREAKOUT");

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(triggerRepo.claimIfStatusEquals(7030L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(20000, 100.1);

        verify(tradeExecutionService).executeTradeFromEntity(trigger);
        verify(historicalService, never()).getTodayOpenPrice(any());
    }

    @Test
    void nonAtrOptionEntrySkipsOpeningGapGuardEvenWhenCapturedOpenIsAboveEntry() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        TriggerTradeRequestEntity trigger = TriggerTradeRequestEntity.builder()
                .id(7031L)
                .symbol("SENSEX")
                .scripCode(1137827)
                .entryPrice(440.0)
                .source("manual")
                .useSpotForEntry(false)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .build();

        when(triggerRepo.findByScripCodeAndStatus(eq(1137827), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(1137827), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(ltpCacheService.getTodayOpeningPrice(1137827)).thenReturn(1830.0);
        when(triggerRepo.claimIfStatusEquals(7031L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name()))
                .thenReturn(1);
        when(tradeExecutionService.executeTradeFromEntity(trigger)).thenReturn(TriggeredTradeSetupEntity.builder().build());

        timedService.evaluatePriceTrigger(1137827, 440.0);

        verify(historicalService, never()).getTodayOpenPrice(any());
        verify(tradeExecutionService).executeTradeFromEntity(trigger);
    }

    @Test
    void doesNotReevaluateARequestWhileEntryExecutionIsInFlight() {
        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 10, 0)).when(timedService).nowIst();
        TriggerTradeRequestEntity trigger = manualSpotTrigger(7032L, 100.0);

        when(triggerRepo.findByScripCodeAndStatus(eq(999999), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of());
        when(triggerRepo.findBySpotScripCodeAndStatus(eq(20000), eq(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)))
                .thenReturn(List.of(trigger));
        when(orderExecutionDispatcher.isInFlight(anyString())).thenReturn(true);

        timedService.evaluatePriceTrigger(20000, 100.1);

        verify(tradeExecutionService, never()).executeTradeFromEntity(any());
        verify(triggerRepo, never()).claimIfStatusEquals(7032L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
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
    void spotCeStopWaitsWhenCurrentSpotHasRecoveredAboveBreachedCandleClose() {
        TriggeredTradeSetupEntity trade = optionTrade(7008L, 999999, 20000);
        trade.setOptionType("CE");
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setUseSpotForSl(true);
        trade.setStopLoss(95.0);
        trade.setEntryAt(LocalDateTime.of(2026, 7, 3, 9, 25));

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(48.0);
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 36), 97.0, 97.0, 94.0, 94.5));
        when(triggeredRepo.findById(7008L)).thenReturn(Optional.of(trade));

        service.monitorOpenTrades(20000, 96.0);

        verify(triggeredRepo, never()).claimIfStatusEquals(eq(7008L), anyString(), anyString(), anyString());
    }

    @Test
    void spotPeStopWaitsWhenCurrentSpotHasRecoveredBelowBreachedCandleClose() {
        TriggeredTradeSetupEntity trade = optionTrade(7009L, 999999, 20000);
        trade.setOptionType("PE");
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setUseSpotForSl(true);
        trade.setStopLoss(105.0);
        trade.setEntryAt(LocalDateTime.of(2026, 7, 3, 9, 25));

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(48.0);
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(
                new LtpCacheService.MinuteCandle(LocalDateTime.of(2026, 7, 3, 9, 36), 103.0, 106.0, 103.0, 105.5));
        when(triggeredRepo.findById(7009L)).thenReturn(Optional.of(trade));

        service.monitorOpenTrades(20000, 104.0);

        verify(triggeredRepo, never()).claimIfStatusEquals(eq(7009L), anyString(), anyString(), anyString());
    }

    @Test
    void spotTargetIsEvaluatedBeforeFirstCompletedSpotCandleExists() {
        TriggeredTradeSetupEntity trade = optionTrade(7010L, 999999, 20000);
        trade.setOptionType("PE");
        trade.setUseSpotForSl(true);
        trade.setUseSpotForTarget(true);
        trade.setStopLoss(105.0);
        trade.setTarget1(100.0);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(120.0);
        when(ltpCacheService.getLastCompletedMinuteCandle(20000)).thenReturn(null);
        when(triggeredRepo.findById(7010L)).thenReturn(Optional.of(trade));
        when(tradeExecutionService.hasUsableTradedExitPrice(trade, 120.0)).thenReturn(true);
        when(triggeredRepo.claimIfStatusEquals(7010L, TriggeredTradeStatus.EXECUTED.name(),
                TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT")).thenReturn(0);

        service.monitorOpenTrades(20000, 99.0);

        verify(triggeredRepo).claimIfStatusEquals(7010L, TriggeredTradeStatus.EXECUTED.name(),
                TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
        verify(triggeredRepo, never()).claimIfStatusEquals(7010L, TriggeredTradeStatus.EXECUTED.name(),
                TriggeredTradeStatus.EXIT_TRIGGERED.name(), "STOP_LOSS_HIT");
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

    @Test
    void rejectsCachedOptionStopLossTickWhenFreshShoonyaQuoteIsAboveStop() {
        TriggeredTradeSetupEntity trade = optionTrade(5212L, 999999, 20000);
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setStopLoss(33.8);

        ScriptMasterEntity option = ScriptMasterEntity.builder()
                .scripCode(999999)
                .exchange("NF")
                .optionType("CE")
                .tradingSymbol("AUBANK25AUG26C1060")
                .build();
        ShoonyaQuoteService shoonyaQuoteService = mock(ShoonyaQuoteService.class);
        ReflectionTestUtils.setField(service, "shoonyaQuoteService", shoonyaQuoteService);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of(trade));
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of());
        when(triggeredRepo.findById(5212L)).thenReturn(Optional.of(trade));
        when(scriptMasterRepository.findByScripCode(999999)).thenReturn(option);
        when(shoonyaQuoteService.getOptionQuote(option)).thenReturn(Optional.of(
                new ShoonyaQuoteService.LiveQuote("AUBANK25AUG26C1060", "72720", 40.95, 40.45, 40.90)));

        service.monitorOpenTrades(999999, 32.8);

        verify(triggeredRepo, never()).claimIfStatusEquals(eq(5212L), anyString(), anyString(), anyString());
        verify(ltpCacheService).updateLtp(999999, 40.95);
    }

    @Test
    void spotTargetKeepsLongOptionOpenWhenPremiumIsBelowActualEntry() {
        TriggeredTradeSetupEntity trade = optionTrade(5211L, 999999, 20000);
        trade.setUseSpotForTarget(true);
        trade.setTarget1(23400.0);
        trade.setActualEntryPrice(111.2);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(100.0);
        when(triggeredRepo.findById(5211L)).thenReturn(Optional.of(trade));

        service.monitorOpenTrades(20000, 23357.0);

        verify(triggeredRepo, never()).claimIfStatusEquals(eq(5211L), anyString(), anyString(), anyString());
        verify(tradeExecutionService, never()).squareOff(any(), anyDouble(), anyString());
    }

    @Test
    void monitorOpenTradesRecoversExitTriggeredPartialWithoutExitOrder() {
        TriggeredTradeSetupEntity trade = optionTrade(6353L, 999999, 20000);
        trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
        trade.setExitReason("TARGET_HIT_PARTIAL");
        trade.setExitOrderId(null);
        trade.setExitClaimedAt(LocalDateTime.of(2026, 7, 3, 9, 29));
        trade.setQuantity(1275L);
        trade.setLots(1);
        trade.setOriginalLots(2);
        trade.setTslEnabled(true);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of(trade));
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of());
        when(triggeredRepo.findById(6353L)).thenReturn(Optional.of(trade));

        service.monitorOpenTrades(999999, 15.95);

        verify(triggeredRepo).findByScripCodeAndStatusIn(eq(999999), argThat(statuses ->
                statuses != null && statuses.contains(TriggeredTradeStatus.EXIT_TRIGGERED)));
        verify(tradeExecutionService).squareOff(trade, 15.95, "TARGET_HIT_PARTIAL");
        verify(triggeredRepo, never()).claimIfStatusEquals(eq(6353L), anyString(), anyString(), anyString());
    }

    @Test
    void monitorOpenTradesDoesNotRecoverFreshExitClaimFromAnotherTickWorker() {
        TriggeredTradeSetupEntity trade = optionTrade(6354L, 999999, 20000);
        trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
        trade.setExitReason("TARGET_HIT");
        trade.setExitOrderId(null);
        trade.setExitClaimedAt(LocalDateTime.of(2026, 7, 3, 9, 30));

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of(trade));
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(999999), anyList())).thenReturn(List.of());
        when(triggeredRepo.findById(6354L)).thenReturn(Optional.of(trade));

        PriceTriggerService timedService = spy(service);
        doReturn(LocalDateTime.of(2026, 7, 3, 9, 30, 15)).when(timedService).nowIst();

        timedService.monitorOpenTrades(999999, 15.95);

        verify(tradeExecutionService, never()).squareOff(trade, 15.95, "TARGET_HIT");
    }

    private void stubIntradayCandle(int hour,
                                    int minute,
                                    double open,
                                    double high,
                                    double low,
                                    double close) {
        when(intradayCandleService.getCompletedMinuteCandle(eq(20000), any(LocalDateTime.class)))
                .thenReturn(new MStockIntradayCandleService.IntradayCandle(
                        LocalDate.of(2026, 7, 3), LocalTime.of(hour, minute),
                        open, high, low, close, 1_000L));
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

    private org.com.sharekhan.entity.TriggerTradeRequestEntity manualSpotTrigger(Long id, double entry) {
        return org.com.sharekhan.entity.TriggerTradeRequestEntity.builder()
                .id(id)
                .symbol("TEST")
                .scripCode(999999)
                .spotScripCode(20000)
                .optionType("CE")
                .entryPrice(entry)
                .source("manual")
                .useSpotForEntry(true)
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
