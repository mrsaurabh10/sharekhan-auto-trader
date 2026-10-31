package org.com.sharekhan.strategy.run;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class StrategyRunServiceTest {
    final StrategyRunRepository runs=mock(StrategyRunRepository.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final StrategySubscriptionService subscriptions=mock(StrategySubscriptionService.class);
    final StrategyRunService service=new StrategyRunService(runs,trades,requests,execution,subscriptions);
    final StrategyRun am=StrategyRun.builder().id("am").templateId("EXPIRY_LONG_STRADDLE").appUserId(1L).brokerCredentialsId(2L).subscriptionId(3L).instance("AM").status("OPEN").tradingDay(LocalDate.now()).createdAt(LocalDateTime.now()).build();
    TriggeredTradeSetupEntity leg(long id,TriggeredTradeStatus status) {return TriggeredTradeSetupEntity.builder().id(id).strategyRunId("am").appUserId(1L).brokerCredentialsId(2L).optionType(id==1?"CE":"PE").quantity(65L).actualEntryPrice(100d).status(status).build();}
    @BeforeEach void setup() {
        when(runs.findById("am")).thenReturn(Optional.of(am));
        when(runs.requestClose(eq("am"),anyString(),any())).thenAnswer(call->{am.setStatus("CLOSING");am.setExitReason(call.getArgument(1));return 1;});
    }
    @Test void oneClickLatchesCloseBeforeEitherExitAndOnlyTouchesThisRun() {
        when(trades.findByStrategyRunId("am")).thenReturn(List.of(leg(1,TriggeredTradeStatus.EXECUTED),leg(2,TriggeredTradeStatus.EXECUTED)));
        service.close("am",1L,false);
        var order=inOrder(runs,execution);order.verify(runs).requestClose(eq("am"),eq("STRATEGY_MANUAL_CLOSE"),any());
        order.verify(execution).squareOffStrategyRunTrade(1L,"am","STRATEGY_MANUAL_CLOSE");order.verify(execution).squareOffStrategyRunTrade(2L,"am","STRATEGY_MANUAL_CLOSE");
        verify(trades,never()).findByStrategyRunId("pm");verifyNoInteractions(subscriptions);verify(runs,never()).markClosed(anyString(),any());
    }
    @Test void repeatedCloseUsesExistingRunAndOneFailedExitDoesNotBlockOtherLeg() {
        when(trades.findByStrategyRunId("am")).thenReturn(List.of(leg(1,TriggeredTradeStatus.EXIT_ORDER_PLACED),leg(2,TriggeredTradeStatus.EXECUTED)));
        doThrow(new IllegalStateException("timeout")).when(execution).squareOffStrategyRunTrade(eq(1L),eq("am"),anyString());
        service.close("am",1L,false);service.close("am",1L,false);
        verify(execution,times(2)).squareOffStrategyRunTrade(2L,"am","STRATEGY_MANUAL_CLOSE");verify(runs,never()).markClosed(anyString(),any());
    }
    @Test void crossUserOrCorruptMembershipCannotCloseForeignPositions() {
        assertThatThrownBy(()->service.close("am",9L,false)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(execution);
        var foreign=leg(1,TriggeredTradeStatus.EXECUTED);foreign.setAppUserId(9L);when(trades.findByStrategyRunId("am")).thenReturn(List.of(foreign));
        assertThatThrownBy(()->service.close("am",1L,false)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(execution);
    }
    @Test void pendingEntryCancellationDoesNotInventFlatAndLateFillIsExited() {
        var pending=leg(1,TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);when(trades.findByStrategyRunId("am")).thenReturn(List.of(pending));
        service.close("am",1L,false);verify(execution).cancelStrategyRunEntry(1L,"am");verify(runs,never()).markClosed(anyString(),any());
        pending.setStatus(TriggeredTradeStatus.EXECUTED);service.close("am",1L,false);verify(execution).squareOffStrategyRunTrade(1L,"am","STRATEGY_MANUAL_CLOSE");
    }
    @Test void unsubmittedRequestCancelledButUnknownSubmissionKeepsClosing() {
        var request=TriggerTradeRequestEntity.builder().id(4L).strategyRunId("am").appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build();
        when(requests.findByStrategyRunId("am")).thenReturn(List.of(request));service.close("am",1L,false);
        verify(requests).claimIfStatusEqualsWithOutcome(eq(4L),eq("PLACED_PENDING_CONFIRMATION"),eq("CANCELLED"),eq("STRATEGY_RUN_CLOSED"),anyString());
        request.setStatus(TriggeredTradeStatus.FAILED);request.setReason("ENTRY_SUBMISSION_STATE_UNKNOWN");service.close("am",1L,false);verify(runs,never()).markClosed(anyString(),any());
    }
    @Test void onlyConfirmedFlatMembershipClosesRun() {
        when(trades.findByStrategyRunId("am")).thenReturn(List.of(leg(1,TriggeredTradeStatus.EXITED_SUCCESS),leg(2,TriggeredTradeStatus.CANCELLED)));
        service.close("am",1L,false);verify(runs).markClosed(eq("am"),any());verify(execution,never()).squareOffStrategyRunTrade(anyLong(),anyString(),anyString());
    }
    @Test void allAndPausePausesBeforeRequestingAnyRunClose() {
        when(runs.findBySubscriptionIdAndStatusIn(eq(3L),anyList())).thenReturn(List.of(am));service.closeAllAndPause(3L,1L,false);
        var order=inOrder(subscriptions,runs);order.verify(subscriptions).cancel(3L,1L,false);order.verify(runs).requestClose(eq("am"),eq("STRATEGY_CLOSE_ALL"),any());
    }
    @Test void aggregateCombinesRealizedAndFullOpenLegsBeforeRunPagination() {
        var closed=leg(1,TriggeredTradeStatus.EXITED_SUCCESS);closed.setExitPrice(110d);closed.setPnl(650d);
        var open=leg(2,TriggeredTradeStatus.EXECUTED);var now=Instant.now();
        when(execution.strategyProfitExitQuote(eq(open),any())).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().bestBid(120d).bestAsk(121d).lastBookAt(now).build()));
        when(trades.findByStrategyRunId("am")).thenReturn(List.of(closed,open));
        when(runs.findByAppUserIdOrderByCreatedAtDesc(eq(1L),any())).thenReturn(new PageImpl<>(List.of(am)));
        var view=service.list(1L,false,0,10).getContent().getFirst();
        assertThat(view.pnl().realizedGross()).isEqualByComparingTo("650");assertThat(view.pnl().openGross()).isEqualByComparingTo("1300");assertThat(view.pnl().totalGross()).isEqualByComparingTo("1950");assertThat(view.positions()).hasSize(2);
    }
    @Test void cashShortRunUsesCurrentEstimateInsteadOfHistoricalLotBasedCost() {
        var closed=leg(1,TriggeredTradeStatus.EXITED_SUCCESS);closed.setExchange("NC");closed.setOptionType(null);
        closed.setEntryAction("SELL");closed.setIntraday(true);closed.setActualEntryPrice(125.65);closed.setExitPrice(126.5);
        closed.setQuantity(1327L);closed.setLots(1327);closed.setOriginalLots(3982);closed.setTargetOrderGroupId(10494L);
        closed.setPnl(-1127.95);closed.setTradeCost(15969.08);closed.setEffectivePnl(-17097.03);
        var pnl=service.pnl(List.of(closed),Instant.now());
        assertThat(pnl.estimatedCosts()).isLessThan(new java.math.BigDecimal("100"));
        assertThat(pnl.estimatedNet()).isGreaterThan(new java.math.BigDecimal("-1300"));
        assertThat(closed.getTradeCost()).isEqualTo(15969.08);
    }

    @Test void unknownQuoteDoesNotDisplayFalseZeroOrActivateRisk() {
        var value=service.pnl(List.of(leg(1,TriggeredTradeStatus.EXECUTED)),Instant.now());assertThat(value.totalGross()).isNull();assertThat(value.marksFresh()).isFalse();
    }

    @Test void averagingShowsReservedPaperNetAndBypassesGenericGrossTarget() {
        am.setTemplateId(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE);
        var position=leg(1,TriggeredTradeStatus.EXECUTED);position.setExchange("MX");position.setQuantity(1250L);
        when(trades.findByStrategyRunId("am")).thenReturn(List.of(position));
        when(execution.strategyProfitExitQuote(eq(position),any())).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().bestBid(101d).bestAsk(101.05d).lastBookAt(Instant.now()).build()));
        var view=service.view(am);assertThat(view.pnl().totalGross()).isEqualByComparingTo("1250");
        assertThat(view.pnl().estimatedCosts()).isEqualByComparingTo("100");assertThat(view.pnl().estimatedNet()).isEqualByComparingTo("1150");
        assertThat(view.pnlBasis()).contains("PAPER_NET_INR");
        var risk=new StrategyRiskSettings();risk.setProfitTarget(new java.math.BigDecimal("1250"));am.setRiskSettings(risk);
        when(runs.findByStatusIn(anyList())).thenReturn(List.of(am));
        service.monitor(am.getTradingDay().atTime(12,0));verify(runs,never()).requestClose(anyString(),eq("STRATEGY_PROFIT_TARGET"),any());
        position.setTradeCost(Double.NaN);assertThat(service.view(am).pnl().estimatedNet()).isNull();
    }

    private void naturalGasRun() {
        am.setTemplateId(org.com.sharekhan.strategy.mcx.NaturalGasSession.TEMPLATE);
        am.setTradingDay(LocalDate.of(2026,10,26));
        when(runs.findByStatusIn(anyList())).thenReturn(List.of(am));
    }
    @Test void mcxRiskContinuesInEveningAndEquityRunStaysOutsideItsSession() {
        naturalGasRun();var risk=new StrategyRiskSettings();risk.setMaxLoss(new java.math.BigDecimal("100"));am.setRiskSettings(risk);
        var position=leg(1,TriggeredTradeStatus.EXECUTED);position.setExchange("MX");when(trades.findByStrategyRunId("am")).thenReturn(List.of(position));
        when(execution.strategyProfitExitQuote(eq(position),any())).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().bestBid(95d).bestAsk(96d).lastBookAt(Instant.now()).build()));
        service.monitor(LocalDate.of(2026,10,26).atTime(20,0));
        verify(runs).requestClose(eq("am"),eq("STRATEGY_MAX_LOSS"),any());verify(execution).squareOffStrategyRunTrade(1L,"am","STRATEGY_MAX_LOSS");
        assertThat(service.pnl(List.of(position),Instant.now()).estimatedNet()).isNull();
    }
    @Test void mcxDoesNotCloseAtEquitySquareoffButClosesAt2320() {
        naturalGasRun();var position=leg(1,TriggeredTradeStatus.EXECUTED);when(trades.findByStrategyRunId("am")).thenReturn(List.of(position));
        service.monitor(am.getTradingDay().atTime(15,35));verify(execution,never()).squareOffStrategyRunTrade(anyLong(),anyString(),anyString());
        service.monitor(am.getTradingDay().atTime(23,20));verify(execution).squareOffStrategyRunTrade(1L,"am","MCX_SESSION_CLOSE");verify(runs,never()).markClosed(anyString(),any());
    }
    @Test void mcxEntryCutoffCancelsPendingButKeepsFilledPositionUntilSquareoff() {
        naturalGasRun();when(trades.findByStrategyRunId("am")).thenReturn(List.of(leg(1,TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION),leg(2,TriggeredTradeStatus.EXECUTED)));
        service.monitor(am.getTradingDay().atTime(23,0));verify(execution).cancelStrategyRunEntry(1L,"am");verify(execution,never()).squareOffStrategyRunTrade(anyLong(),anyString(),anyString());verify(runs,never()).markClosed(anyString(),any());
    }
    @Test void mcxDoesNotSubmitAfter2330AndOldRunClosesNextOpen() {
        naturalGasRun();when(trades.findByStrategyRunId("am")).thenReturn(List.of(leg(1,TriggeredTradeStatus.EXECUTED)));
        service.monitor(am.getTradingDay().atTime(23,30));verifyNoInteractions(execution);
        service.monitor(am.getTradingDay().plusDays(1).atTime(9,0));verify(execution).squareOffStrategyRunTrade(1L,"am","MCX_SESSION_CLOSE");
    }
    @Test void equityRunDoesNotAcquireEveningExitSchedule() {
        when(runs.findByStatusIn(anyList())).thenReturn(List.of(am));service.monitor(LocalDate.of(2026,10,26).atTime(23,20));verifyNoInteractions(execution);verify(trades,never()).findByStrategyRunId(anyString());
    }
}
