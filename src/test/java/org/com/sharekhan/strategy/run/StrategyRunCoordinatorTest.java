package org.com.sharekhan.strategy.run;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.StrategySupport;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrategyRunCoordinatorTest {
    final StrategyRunRepository runs=mock(StrategyRunRepository.class);
    final StrategySubscriptionRepository subs=mock(StrategySubscriptionRepository.class);
    final EntityManager em=mock(EntityManager.class);
    final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    final Map<String,StrategyRun> saved=new HashMap<>();
    StrategyRunCoordinatorTest() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(runs.findById(any())).thenAnswer(c->Optional.ofNullable(saved.get(c.getArgument(0))));
        when(runs.existsById(any())).thenAnswer(c->saved.containsKey(c.getArgument(0)));
        doAnswer(c->{StrategyRun r=c.getArgument(0);saved.put(r.getId(),r);return null;}).when(em).persist(any());
        when(subs.findById(10L)).thenReturn(Optional.of(StrategySubscriptionEntity.builder().id(10L).appUserId(1L)
            .brokerCredentialsId(2L).templateId("EXPIRY_LONG_STRADDLE").status("ACTIVE").build()));
        when(subs.lockForRunEntry(10L)).thenAnswer(c->subs.findById(10L));
    }
    StrategyRunCoordinator coordinator(String time) {
        return new StrategyRunCoordinator(runs,subs,em,tx,Clock.fixed(LocalDateTime.parse("2026-10-19T"+time).atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
    }
    StrategyApplyRequest request() {
        var r=new StrategyApplyRequest();r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setTemplateId("EXPIRY_LONG_STRADDLE");
        r.setSymbol("NIFTY");r.setSubscriptionId(10L);r.setLots(2);return r;
    }
    @Test void closesOnlyOneDeterministicInstanceAndCapturesRules() {
        var r=request();var rules=new StrategyRiskSettings();rules.setProfitTarget(new BigDecimal("2000"));rules.setAmountMode(StrategyRiskSettings.AmountMode.PER_LOT);r.setRiskSettings(rules);
        var am=coordinator("09:30:15");var entries=new AtomicInteger();
        am.evaluate(r,()->{am.ensureCurrentRun();entries.incrementAndGet();return null;});
        String amId=saved.keySet().iterator().next();
        assertThat(saved.get(amId).getInstance()).isEqualTo("AM");
        assertThat(saved.get(amId).getRiskSettings().scale(saved.get(amId).getRiskSettings().getProfitTarget(),2)).isEqualByComparingTo("4000");
        rules.setProfitTarget(BigDecimal.TEN); // A running instance retains its snapshot.
        am.evaluate(r,()->{am.ensureCurrentRun();entries.incrementAndGet();return null;});
        assertThat(saved).hasSize(1);assertThat(saved.get(amId).getRiskSettings().getProfitTarget()).isEqualByComparingTo("2000");
        saved.get(amId).setStatus("CLOSING");
        assertThat(am.evaluate(r,()->{entries.incrementAndGet();return null;}).getStatus()).isEqualTo("waiting");
        assertThat(entries).hasValue(2);
        var pm=coordinator("13:30:05");pm.evaluate(r,()->{pm.ensureCurrentRun();return null;});
        assertThat(saved.values()).extracting(StrategyRun::getInstance).containsExactlyInAnyOrder("AM","PM");
    }
    @Test void scopeCannotLeakAfterFailureOrBeInjectedByCaller() {
        var am=coordinator("09:30:00");var r=request();
        assertThatThrownBy(()->am.evaluate(r,()->{throw new IllegalStateException("failed evaluator");})).hasMessage("failed evaluator");
        var manual=new TriggerRequest();manual.setStrategyRunId("spoofed");am.attach(manual);
        assertThat(manual.getStrategyRunId()).isNull();assertThat(saved).isEmpty();
        am.evaluate(r,()->{var foreign=new TriggerRequest();foreign.setUserId(99L);foreign.setBrokerCredentialsId(2L);
            assertThatThrownBy(()->am.attach(foreign)).isInstanceOf(IllegalArgumentException.class);return null;});
        r.setUserId(99L);assertThatThrownBy(()->am.evaluate(r,()->null)).hasMessageContaining("scope mismatch");
    }
    @Test void pausedSubscriptionBlocksEntryEvenAfterEvaluationStarted() {
        var am=coordinator("09:30:00");
        am.evaluate(request(),()->{
            subs.findById(10L).orElseThrow().setStatus("CANCELLED");
            assertThatThrownBy(am::ensureCurrentRun).hasMessageContaining("paused");return null;
        });assertThat(saved).isEmpty();
    }
}
