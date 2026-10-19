package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.com.sharekhan.service.StrategyTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Stock0925MoverAtrBreakoutStrategyTest {
    final StrategySupport support = mock(StrategySupport.class);
    final ScriptMasterRepository scripts = mock(ScriptMasterRepository.class);
    final MStockGainerLoserService movers = mock(MStockGainerLoserService.class);
    final Fno925EntryQualificationService qualification = mock(Fno925EntryQualificationService.class);
    final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 10, 0);
    final Clock clock = mock(Clock.class);
    final MoverSelectionSnapshotStore snapshots = mock(MoverSelectionSnapshotStore.class);
    final Stock0925MoverAtrBreakoutStrategy strategy = new Stock0925MoverAtrBreakoutStrategy(support, scripts, movers, qualification, snapshots, clock);
    final ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("ABC").exchange("NC").scripCode(123).build();

    StrategyApplyRequest request(String amount) {
        var r = new StrategyApplyRequest(); r.setTemplateId(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID);
        r.setUserId(1L); r.setBrokerCredentialsId(2L);
        r.setInvestmentPerStock(amount == null ? null : new BigDecimal(amount)); return r;
    }
    Fno925EntryQualificationService.Signal signal() {
        return new Fno925EntryQualificationService.Signal(750d, 740d, 10d, 745d, 730d,
                new StrategyCandle(now.toLocalDate(), LocalTime.of(9,55), 745, 755, 740, 750, 1000L), "MORNING_ORB");
    }
    void setup() {
        // Model the committed shortlist: later evaluations never invoke the supplier again.
        java.util.concurrent.atomic.AtomicReference<List<Fno925Candidate>> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(snapshots.loadOrCapture(eq(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID), any(), any())).thenAnswer(call -> {
            if (saved.get() == null) saved.set(((java.util.function.Supplier<List<Fno925Candidate>>) call.getArgument(2)).get());
            return java.util.Optional.of(saved.get());
        });
        when(clock.getZone()).thenReturn(StrategySupport.MARKET_ZONE);
        when(clock.instant()).thenReturn(now.atZone(StrategySupport.MARKET_ZONE).toInstant());
        when(support.roundPrice(anyDouble())).thenAnswer(c -> c.getArgument(0));
        when(scripts.findDistinctOptionUnderlyingSymbols()).thenReturn(List.of("ABC"));
        when(movers.topGainers()).thenReturn(List.of(new MStockGainerLoserService.Mover("ABC",750,5)));
        when(support.resolveSpotScript("ABC")).thenReturn(spot);
        when(qualification.qualifyWithDirectionalCandle(any(), any())).thenReturn(Fno925EntryQualificationService.Qualification.qualified(signal()));
        when(support.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(10L).build());
        when(support.waiting(any(), any(), anyString())).thenAnswer(c -> StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
    }
    @Test void simulatorPrePlacementWaitDoesNotConsumeTheSymbolAndLaterSignalCanExecute() {
        setup();
        when(support.executeTriggeredTrade(any()))
                .thenThrow(new org.com.sharekhan.exception.EntryNotReadyException("Waiting for simulator buy trigger"))
                .thenReturn(TriggerTradeRequestEntity.builder().id(10L).build());
        assertThat(strategy.apply(request("10000")).getMessage()).contains("Waiting for simulator buy trigger");
        assertThat(strategy.apply(request("10000")).getStatus()).isEqualTo("triggered");
        verify(support, times(2)).executeTriggeredTrade(any());
        verify(support, times(1)).auditTradeRequest(any(), any(), eq("ABC"), eq("CREATED"), any(), any());
    }

    @Test void prePlacementWaitDoesNotPreventOtherCashCandidatesFromExecuting() {
        setup();
        var other = new Fno925Candidate("OTHER", ScriptMasterEntity.builder().exchange("NC").tradingSymbol("OTHER").scripCode(456).build(), "CE");
        when(snapshots.loadOrCapture(anyString(), any(), any())).thenReturn(java.util.Optional.of(List.of(new Fno925Candidate("ABC", spot, "CE"), other)));
        when(support.executeTriggeredTrade(argThat(t -> t != null && "ABC".equals(t.getInstrument()))))
                .thenThrow(new org.com.sharekhan.exception.EntryNotReadyException("Waiting for price"));
        assertThat(strategy.apply(request("10000")).getSymbol()).isEqualTo("OTHER");
        verify(support).executeTriggeredTrade(argThat(t -> "OTHER".equals(t.getInstrument())));
    }

    @Test void buysWholeCashSharesWithAtrExitsAndNoOptionContract() {
        setup();
        {
            var response = new StrategyTemplateService(List.of(strategy)).apply(request("10000"));
            var t = response.getTriggerRequest();
            assertThat(response.getDirection()).isEqualTo("BUY");
            assertThat(t.getExchange()).isEqualTo("NC"); assertThat(t.getQuantity()).isEqualTo(13);
            assertThat(t.getQuantity() * t.getEntryPrice()).isLessThanOrEqualTo(10000d);
            assertThat(t.getLots()).isNull(); assertThat(t.getOptionType()).isNull();
            assertThat(t.getStrikePrice()).isNull(); assertThat(t.getExpiry()).isNull();
            assertThat(t.getStopLoss()).isEqualTo(735d);
            assertThat(t.getTarget1()).isEqualTo(770d); assertThat(t.getTarget2()).isEqualTo(780d); assertThat(t.getTarget3()).isEqualTo(790d);
            assertThat(t.getTslEnabled()).isTrue(); assertThat(t.getUserId()).isEqualTo(1L); assertThat(t.getBrokerCredentialsId()).isEqualTo(2L);
            strategy.apply(request("10000"));
            verify(support, times(1)).executeTriggeredTrade(any());
            verify(movers, times(1)).topGainers(); verify(movers, never()).topLosers();
            verify(support, never()).resolveFnoEntryContract(any(),any(),any(),any(),anyDouble());
        }
    }
    @Test void skipsUnaffordableAndUnqualifiedSharesWithoutSubmitting() {
        setup();
        {
            assertThat(strategy.apply(request("749.99")).getMessage()).contains("below the price of one share");
            when(qualification.qualifyWithDirectionalCandle(any(),any())).thenReturn(Fno925EntryQualificationService.Qualification.waiting("no breakout"));
            assertThat(strategy.apply(request("10000")).getMessage()).contains("no breakout");
            verify(support, never()).executeTriggeredTrade(any());
        }
    }
    @Test void dailyPersistedAttemptPreventsEntryAfterRestart() {
        setup(); when(support.hasStockMoverRequestOn(anyString(), any(), eq(1L), eq("ABC"))).thenReturn(true);
        {
            strategy.apply(request("10000"));
            verify(support, never()).executeTriggeredTrade(any()); verifyNoInteractions(qualification);
        }
    }
    @Test void directionRejectionIsAuditedAndCanRetryOnALaterMatchingCandle() {
        setup();
        String reason = "entry requires a bullish completed five-minute signal candle (close > open)";
        when(qualification.qualifyWithDirectionalCandle(any(), any()))
                .thenReturn(Fno925EntryQualificationService.Qualification.waiting(reason))
                .thenReturn(Fno925EntryQualificationService.Qualification.qualified(signal()));

        assertThat(strategy.apply(request("10000")).getMessage()).contains(reason);
        verify(support, never()).executeTriggeredTrade(any());
        verify(support).auditStrategy(any(), any(), eq("ABC"), eq("STRATEGY_EVALUATION"),
                eq("REJECTED"), eq(reason), isNull(), isNull());

        assertThat(strategy.apply(request("10000")).getStatus()).isEqualTo("triggered");
        verify(qualification, times(2)).qualifyWithDirectionalCandle(any(), eq(now));
        verify(qualification, never()).qualify(any(), any());
        verify(support, times(1)).executeTriggeredTrade(any());
    }
    @Test void snapshotChoosesFivePositiveFnoGainersAndIgnoresDuplicatesAndOtherStocks() {
        setup(); when(scripts.findDistinctOptionUnderlyingSymbols()).thenReturn(List.of("ABC","B","C","D","E","F","DOWN"));
        when(movers.topGainers()).thenReturn(List.of(
                new MStockGainerLoserService.Mover("OTHER",1,99), new MStockGainerLoserService.Mover("ABC",1,8),
                new MStockGainerLoserService.Mover("ABC",1,8), new MStockGainerLoserService.Mover("B",1,7),
                new MStockGainerLoserService.Mover("C",1,6), new MStockGainerLoserService.Mover("D",1,5),
                new MStockGainerLoserService.Mover("E",1,4), new MStockGainerLoserService.Mover("F",1,3),
                new MStockGainerLoserService.Mover("DOWN",1,-1)));
        when(support.resolveSpotScript(anyString())).thenAnswer(c -> ScriptMasterEntity.builder().exchange("NC").tradingSymbol(c.getArgument(0)).build());
        List<Fno925Candidate> selected = ReflectionTestUtils.invokeMethod(strategy,"snapshotUniverse");
        assertThat(selected).extracting(Fno925Candidate::symbol).containsExactly("ABC","B","C","D","E");
        assertThat(selected).allMatch(c -> "CE".equals(c.optionType()));
    }
    @Test void restartedCashStrategyUsesOriginalSnapshotWithoutLookingAtCurrentGainers() {
        setup();
        var original = new Fno925Candidate("ABC", spot, "CE");
        when(snapshots.loadOrCapture(eq(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID), eq(now), any()))
                .thenReturn(java.util.Optional.of(List.of(original)));
        var restarted = new Stock0925MoverAtrBreakoutStrategy(support, scripts, movers, qualification, snapshots, clock);
        assertThat(restarted.apply(request("10000")).getStatus()).isEqualTo("triggered");
        verifyNoInteractions(movers);
        verify(qualification).qualifyWithDirectionalCandle(original, now);
        verify(support).executeTriggeredTrade(argThat(t -> "ABC".equals(t.getInstrument()) && "NC".equals(t.getExchange()) && t.getQuantity() == 13));
    }
    @Test void missingOpeningSnapshotAfterRestartDoesNotSelectLaterMoversOrSubmit() {
        setup();
        when(snapshots.loadOrCapture(eq(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID), eq(now), any()))
                .thenReturn(java.util.Optional.empty());
        assertThat(strategy.apply(request("10000")).getMessage()).contains("Missing original 09:25 stock mover snapshot");
        verifyNoInteractions(movers, qualification);
        verify(support, never()).executeTriggeredTrade(any());
    }
    @Test void storedEmptyOpeningListDoesNotBecomeANewSelectionAfterRestart() {
        setup();
        when(snapshots.loadOrCapture(eq(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID), eq(now), any()))
                .thenReturn(java.util.Optional.of(List.of()));
        assertThat(strategy.apply(request("10000")).getMessage()).contains("No eligible stock movers");
        verifyNoInteractions(movers, qualification);
        verify(support, never()).executeTriggeredTrade(any());
    }
    @Test void restartedCashStrategyStillHonorsPersistedDailyAttemptGuard() {
        setup();
        when(snapshots.loadOrCapture(eq(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID), eq(now), any()))
                .thenReturn(java.util.Optional.of(List.of(new Fno925Candidate("ABC", spot, "CE"))));
        when(support.hasStockMoverRequestOn(anyString(), any(), eq(1L), eq("ABC"))).thenReturn(true);
        var restarted = new Stock0925MoverAtrBreakoutStrategy(support, scripts, movers, qualification, snapshots, clock);
        restarted.apply(request("10000"));
        verifyNoInteractions(movers, qualification);
        verify(support, never()).executeTriggeredTrade(any());
    }
    @ParameterizedTest @ValueSource(strings={"09:24","15:20","15:29"})
    void doesNotEnterOutsideWindow(String clockText) {
        setup(); LocalDateTime at = now.toLocalDate().atTime(LocalTime.parse(clockText));
        {
            when(clock.instant()).thenReturn(at.atZone(StrategySupport.MARKET_ZONE).toInstant());
            assertThat(strategy.apply(request("10000")).getStatus()).isEqualTo("waiting");
            verifyNoInteractions(movers, qualification); verify(support, never()).executeTriggeredTrade(any());
        }
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.001","1000000000"})
    void rejectsInvalidAmounts(String amount) {
        assertThatThrownBy(() -> new StrategyTemplateService(List.of(strategy)).apply(request(amount))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(movers, qualification);
    }
    @Test void rejectsMissingAmount() {
        assertThatThrownBy(() -> strategy.apply(request(null))).isInstanceOf(IllegalArgumentException.class);
    }
}
