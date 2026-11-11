package org.com.sharekhan.strategy.adxdmi;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties="spring.jpa.show-sql=false",showSql=false)
@Import(AdxDmiIndexExitPolicy.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AdxDmiIndexExitPersistenceTest {
    static final LocalDateTime START=LocalDateTime.of(2026,11,11,10,0);
    @Autowired AdxDmiIndexExitPolicy policy;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    List<TriggeredTradeSetupEntity> group;
    @BeforeEach void setup() {
        trades.deleteAll();brokers.deleteAll();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->em.createQuery("delete from AdxDmiExitState").executeUpdate());
        var b=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(3L).brokerName("Simulator").active(true).build());
        group=new ArrayList<>();
        for(int leg=1;leg<=3;leg++)group.add(trades.saveAndFlush(TriggeredTradeSetupEntity.builder().appUserId(3L).brokerCredentialsId(b.getId())
                .symbol("NIFTY").strategyRunId("run").source(AdxDmiRules.PREMIUM_SOURCE).status(TriggeredTradeStatus.EXECUTED)
                .entryAt(START.plusSeconds(leg-1)).optionType("CE").stopLoss(90d).target1(100d+10*leg)
                .comment("ADX_EXIT="+AdxDmiIndexExitRules.VERSION+";ADX_SIGNAL=2026-11-11T10:00:CE;ADX_INDEX_T1=110;ADX_LEG="+leg+";").build()));
    }
    @Test void t1ObservationIsSharedAcrossLegsAndRetainedBetweenTransactions() {
        assertEquals(AdxDmiIndexExitRules.TARGET,policy.observe(group.getFirst().getId(),110d,START.plusMinutes(1)));
        assertNull(policy.observe(group.get(1).getId(),105d,START.plusMinutes(20)));
        assertNull(policy.observe(group.get(2).getId(),105d,START.plusMinutes(20)));
        assertEquals(AdxDmiIndexExitRules.STOP,policy.observe(group.get(1).getId(),89d,START.plusMinutes(21)));
        assertEquals(AdxDmiIndexExitRules.STOP,policy.observe(group.get(1).getId(),130d,START.plusMinutes(22)));
        assertEquals(90d,trades.findById(group.get(1).getId()).orElseThrow().getStopLoss());
    }
    @Test void timeoutUsesFirstFillAndPersistsWithoutQuoteThroughRecovery() {
        Long id=group.get(2).getId();
        assertNull(policy.observe(id,null,START.plusMinutes(14).plusSeconds(59)));
        assertEquals(AdxDmiIndexExitRules.TIMEOUT,policy.observe(id,null,START.plusMinutes(15)));
        assertEquals(AdxDmiIndexExitRules.TIMEOUT,policy.observe(group.getFirst().getId(),140d,START.plusMinutes(16)));
        assertEquals(AdxDmiIndexExitRules.TIMEOUT,policy.observe(id,140d,START.plusMinutes(20)));
    }
    @Test void concurrentLegObservationsCreateExactlyOneDurableState() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return policy.observe(group.get(1).getId(),105d,START.plusMinutes(1));});
            var b=pool.submit(()->{gate.await();return policy.observe(group.get(2).getId(),105d,START.plusMinutes(1));});
            gate.countDown();assertNull(a.get(10,TimeUnit.SECONDS));assertNull(b.get(10,TimeUnit.SECONDS));
        }
        var count=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->em.createQuery("select count(s) from AdxDmiExitState s",Long.class).getSingleResult());
        assertEquals(1L,count);
    }
    @Test void signalAccountAndSymbolHaveIndependentStateAndClosedLegsCannotBeReopened() {
        var t=group.get(1);t.setSymbol("SENSEX");t=trades.saveAndFlush(t);
        policy.observe(group.getFirst().getId(),110d,START.plusMinutes(1));
        assertEquals(AdxDmiIndexExitRules.TIMEOUT,policy.observe(t.getId(),105d,START.plusMinutes(16)));
        t=trades.findById(group.getFirst().getId()).orElseThrow();t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);trades.saveAndFlush(t);
        assertNull(policy.observe(t.getId(),89d,START.plusMinutes(20)));
    }
}
