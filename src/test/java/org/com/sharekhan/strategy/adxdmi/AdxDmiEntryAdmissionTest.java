package org.com.sharekhan.strategy.adxdmi;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@DataJpaTest(properties="spring.jpa.show-sql=false",showSql=false)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AdxDmiEntryAdmissionTest {
    @Autowired EntityManager em;
    @Autowired TriggerTradeRequestRepository requests;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired StrategyRunRepository runs;
    @Autowired StrategySubscriptionRepository subs;
    @Autowired PlatformTransactionManager manager;
    AdxDmiEntryAdmission admission;TriggerRequest order;
    @BeforeEach void setup(){
        trades.deleteAll();requests.deleteAll();runs.deleteAll();brokers.deleteAll();
        var account=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").active(true).build());
        admission=new AdxDmiEntryAdmission(em,requests,trades,runs,subs,manager,Clock.fixed(Instant.parse("2026-11-11T04:31:00Z"),AdxDmiRules.IST));
        order=new TriggerRequest();order.setUserId(1L);order.setBrokerCredentialsId(account.getId());order.setInstrument("NIFTY");order.setSource(AdxDmiRules.PREMIUM_SOURCE);
        order.setStrategyDecision("ADX_SIGNAL=2026-11-11T10:00:CE;ADX_DEADLINE=2026-11-11T10:05;");
    }
    TriggerTradeRequestEntity save(){return requests.saveAndFlush(TriggerTradeRequestEntity.builder().appUserId(1L).brokerCredentialsId(order.getBrokerCredentialsId())
            .symbol("NIFTY").source(order.getSource()).comment(order.getStrategyDecision()).createdAt(LocalDateTime.of(2026,11,11,10,1)).status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build());}
    @Test void concurrentCallsPersistOnlyOneStandardRequestAndRestartDoesNotResubmit()throws Exception{
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<AdxDmiEntryAdmission.Admission> job=()->{start.await();return admission.prepare(order,this::save);};
            var a=pool.submit(job);var b=pool.submit(job);start.countDown();
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertNotEquals(first.created(),second.created());assertEquals(first.request().getId(),second.request().getId());
        }
        assertEquals(1,requests.count());
        assertFalse(admission.prepare(order,()->{fail("Must not prepare duplicate after restart");return null;}).created());
    }
    @Test void batchCreatesThreeDistinctNormalRequestsAndNeverCreatesTradesItself(){
        var result=admission.prepareBatch(java.util.List.of(order,order,order),ignored->save());
        assertTrue(result.created());assertEquals(3,result.requests().size());
        assertEquals(3,result.requests().stream().map(TriggerTradeRequestEntity::getId).distinct().count());
        assertTrue(result.requests().stream().allMatch(r->r.getStatus()==TriggeredTradeStatus.TRIGGERED));
        assertEquals(3,requests.count());assertEquals(0,trades.count());
        assertFalse(admission.prepareBatch(java.util.List.of(order,order,order),ignored->{fail("Duplicate batch");return null;}).created());
        assertEquals(3,requests.count());
    }
    @Test void failedPreparationRollsBackAllThreeBeforeAnyBrokerExecution(){
        var count=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,()->admission.prepareBatch(java.util.List.of(order,order,order),ignored->{
            if(count.incrementAndGet()==2)throw new IllegalStateException("preparation failed");return save();
        }));
        assertEquals(0,requests.count());assertEquals(0,trades.count());
    }
    @Test void anUnresolvedEarlierRequestBlocksAnotherSignal(){
        var old=save();old.setComment("ADX_SIGNAL=2026-11-11T09:55:CE;");requests.saveAndFlush(old);
        assertThrows(IllegalStateException.class,()->admission.prepare(order,this::save));assertEquals(1,requests.count());
    }
    @Test void closedPositionAllowsLaterSignalButNotSameSignal(){
        var old=save();old.setStatus(TriggeredTradeStatus.EXECUTED);requests.saveAndFlush(old);
        trades.saveAndFlush(TriggeredTradeSetupEntity.builder().triggerRequestId(old.getId()).appUserId(1L).brokerCredentialsId(order.getBrokerCredentialsId())
                .symbol("NIFTY").source(order.getSource()).status(TriggeredTradeStatus.EXITED_SUCCESS).exitedAt(LocalDateTime.of(2026,11,11,9,59)).build());
        assertFalse(admission.prepare(order,this::save).created());
        old.setComment("ADX_SIGNAL=2026-11-11T09:55:CE;");requests.saveAndFlush(old);
        assertTrue(admission.prepare(order,this::save).created());assertEquals(2,requests.count());
    }
    @Test void closedRunPreventsPlacementWithoutCallingBroker(){
        runs.saveAndFlush(StrategyRun.builder().id("closed").appUserId(1L).brokerCredentialsId(order.getBrokerCredentialsId()).templateId(AdxDmiRules.TEMPLATE)
                .symbol("NIFTY").tradingDay(LocalDate.of(2026,11,11)).instance("DAY").status("CLOSED").createdAt(LocalDateTime.of(2026,11,11,10,0)).build());
        var trade=TriggeredTradeSetupEntity.builder().strategyRunId("closed").appUserId(1L).brokerCredentialsId(order.getBrokerCredentialsId()).comment(order.getStrategyDecision()).build();
        assertThrows(IllegalStateException.class,()->admission.place(trade,()->{fail("No broker call for closed run");return null;}));
    }
}
