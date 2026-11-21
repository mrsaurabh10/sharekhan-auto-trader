package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.com.sharekhan.enums.TriggeredTradeStatus.*;

@DataJpaTest
class TriggerTradeRequestRepositoryCleanupTest {
    @Autowired TriggerTradeRequestRepository repository;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired StrategyRunRepository runs;
    @Autowired EntityManager entityManager;
    private final LocalDateTime cutoff = LocalDateTime.of(2026,10,30,23,30);

    private int purge() {
        return repository.deleteCompletedDayRequests(cutoff,
            List.of(PLACED_PENDING_CONFIRMATION, ENTRY_SUBMITTING, TRIGGERED, TARGET_ORDER_PLACED,
                    EXIT_TRIGGERED, EXIT_ORDER_PLACED, EXIT_FAILED),
            List.of(REJECTED,CANCELLED,FAILED,COMPLETED,EXITED_SUCCESS),EXECUTED);
    }
    private TriggerTradeRequestEntity request(TriggeredTradeStatus status) {
        return repository.saveAndFlush(TriggerTradeRequestEntity.builder().symbol("NATURALGAS")
            .source("strategy:MCX_NATURALGAS_ATM_BREAKOUT").intraday(true).status(status)
            .createdAt(cutoff.minusHours(1)).build());
    }
    private void trade(TriggerTradeRequestEntity request, TriggeredTradeStatus status) {
        trades.saveAndFlush(TriggeredTradeSetupEntity.builder().triggerRequestId(request.getId())
            .symbol("NATURALGAS").status(status).build());
    }
    @Test void clearsLateNaturalGasRequestsAndPreservesTradeHistory() {
        var filled=request(EXECUTED);trade(filled,EXITED_SUCCESS);
        var rejected=request(REJECTED);
        var undated=request(CANCELLED);undated.setCreatedAt(null);repository.saveAndFlush(undated);
        var future=request(CANCELLED);future.setCreatedAt(cutoff);repository.saveAndFlush(future);
        assertThat(purge()).isEqualTo(3);entityManager.clear();
        assertThat(repository.findAll()).extracting(TriggerTradeRequestEntity::getId).containsExactly(future.getId());
        assertThat(trades.findAll()).hasSize(1);
    }
    @Test void retainsUnresolvedEntriesOpenLegsAndUnknownOutcomes() {
        request(PLACED_PENDING_CONFIRMATION);request(ENTRY_SUBMITTING);request(TRIGGERED);
        request(EXECUTED); // Orphan fill must be reconciled, not discarded.
        var open=request(EXECUTED);trade(open,EXECUTED);trade(open,EXITED_SUCCESS);
        var failedExit=request(EXECUTED);trade(failedExit,EXIT_FAILED);
        var unknown=request(FAILED);unknown.setReason("ENTRY_STATUS_UNKNOWN");repository.saveAndFlush(unknown);
        var uncertain=request(EXECUTED);trade(uncertain,FAILED);
        var leg=trades.findAll().stream().filter(t->uncertain.getId().equals(t.getTriggerRequestId())).findFirst().orElseThrow();
        leg.setReason("CANCELLATION_UNCONFIRMED");trades.saveAndFlush(leg);
        assertThat(purge()).isZero();assertThat(repository.count()).isEqualTo(8);
    }
    @Test void retainsRequestsUntilStrategyRunIsClosed() {
        var run=runs.saveAndFlush(StrategyRun.builder().id("test-run").appUserId(1L).brokerCredentialsId(1L)
            .templateId("MCX_NATURALGAS_SCALPING").symbol("NATURALGAS").tradingDay(cutoff.toLocalDate())
            .instance("1").status("CLOSING").createdAt(cutoff.minusHours(2)).build());
        var r=request(EXECUTED);r.setStrategyRunId(run.getId());repository.saveAndFlush(r);trade(r,EXITED_SUCCESS);
        assertThat(purge()).isZero();run.setStatus("CLOSED");runs.saveAndFlush(run);
        assertThat(purge()).isEqualTo(1);
    }
}
