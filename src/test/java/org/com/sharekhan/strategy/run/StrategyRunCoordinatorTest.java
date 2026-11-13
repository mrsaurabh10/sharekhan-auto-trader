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
    @Test void approvedRetryGetsOneDistinctRunWithoutReopeningTheOriginal(){
        var c=coordinator("12:30:00");var r=request();r.setTemplateId("BATMAN_MONTHLY");subs.findById(10L).orElseThrow().setTemplateId("BATMAN_MONTHLY");
        c.evaluate(r,()->{c.ensureCurrentRun();return null;});var original=saved.values().iterator().next();original.setStatus("CLOSED");
        r.setBatmanManualEntryDay(LocalDate.of(2026,10,19));r.setBatmanRetryOfRunId(original.getId());
        c.evaluate(r,()->{c.ensureCurrentRun();return null;});assertThat(saved).hasSize(2);assertThat(original.getStatus()).isEqualTo("CLOSED");
        c.evaluate(r,()->{c.ensureCurrentRun();return null;});assertThat(saved).hasSize(2);assertThat(saved.values()).extracting(StrategyRun::getInstance).containsExactlyInAnyOrder("DAY","MANUAL_RETRY");
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
    @Test void simulatorSupertrendRunCapturesPerOriginalLotCapAndRetainsItAfterPartialBooking() {
        var c=coordinator("09:30:15");var r=request();r.setTemplateId("ST_RSI_EMA_ADX_PE");r.setLots(3);
        subs.findById(10L).orElseThrow().setTemplateId(r.getTemplateId());
        var brokers=mock(BrokerCredentialsRepository.class);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").build()));
        var policy=new org.com.sharekhan.strategy.SupertrendSimulatorPolicy(brokers,mock(ScriptMasterRepository.class),true);
        org.springframework.test.util.ReflectionTestUtils.setField(c,"supertrendPolicy",policy);
        c.evaluate(r,()->{c.ensureCurrentRun();return null;});
        var run=saved.values().iterator().next();
        assertThat(run.getLots()).isEqualTo(3);
        assertThat(run.getRiskSettings().getMaxLoss()).isEqualByComparingTo("3000");
        var risk=new StrategyRunRiskEngine();
        assertThat(risk.evaluate(run.getRiskSettings(),run.getLots(),new BigDecimal("-8999.99"),null,null).closeReason()).isNull();
        assertThat(risk.evaluate(run.getRiskSettings(),run.getLots(),new BigDecimal("-9000"),null,null).closeReason()).isEqualTo("STRATEGY_MAX_LOSS");
        r.setLots(1);c.evaluate(r,()->{c.ensureCurrentRun();return null;});
        assertThat(saved).hasSize(1);assertThat(run.getLots()).isEqualTo(3);
        assertThat(run.getRiskSettings().scale(run.getRiskSettings().getMaxLoss(),run.getLots())).isEqualByComparingTo("9000");
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PAUSED", "CANCELLED"})
    void pausedSubscriptionBlocksEntryEvenAfterEvaluationStarted(String status) {
        var am=coordinator("09:30:00");
        am.evaluate(request(),()->{
            subs.findById(10L).orElseThrow().setStatus(status);
            assertThatThrownBy(am::ensureCurrentRun).hasMessageContaining("paused");return null;
        });assertThat(saved).isEmpty();
    }

    @Test void averagingReusesOpenBasketAndCreatesNextOnlyAfterConfirmedClosure() {
        var c=coordinator("11:00:05");var r=request();r.setTemplateId(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE);r.setSymbol("NATURALGAS");r.setLots(4);
        org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.validate(r);
        subs.findById(10L).orElseThrow().setTemplateId(r.getTemplateId());
        when(runs.findByAppUserIdAndBrokerCredentialsIdAndTemplateIdAndTradingDayOrderByCreatedAtAsc(any(),any(),any(),any()))
            .thenAnswer(x->saved.values().stream().sorted(Comparator.comparing(StrategyRun::getInstance)).toList());
        assertThatThrownBy(c::currentId).hasMessageContaining("context");
        c.evaluate(r,()->{assertThat(saved).isEmpty();c.ensureCurrentRun();return null;});
        var first=saved.values().iterator().next();assertThat(first.getInstance()).isEqualTo("BASKET1");
        c.evaluate(r,()->{assertThat(c.currentId()).isEqualTo(first.getId());c.ensureCurrentRun();return null;});assertThat(saved).hasSize(1);
        first.setStatus("CLOSING");assertThat(c.evaluate(r,()->{throw new AssertionError("must not evaluate a closing basket");}).getStatus()).isEqualTo("waiting");
        first.setStatus("CLOSED");c.evaluate(r,()->{c.ensureCurrentRun();return null;});
        assertThat(saved).hasSize(2);assertThat(saved.values()).extracting(StrategyRun::getInstance).containsExactlyInAnyOrder("BASKET1","BASKET2");
    }
}
