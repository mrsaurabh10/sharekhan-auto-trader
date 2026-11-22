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
    @Test void immutableSignalSurvivesEveryTerminalFailureCommentAndRestart(){
        var old=save();
        // Simulate initial insertion of a new-format request, not an update to an immutable column.
        requests.deleteById(old.getId());
        old.setId(null);old.setStrategyDecision(order.getStrategyDecision());old.setStatus(TriggeredTradeStatus.TRIGGERED);
        old=requests.saveAndFlush(old);
        assertEquals(1,requests.claimIfStatusEqualsWithOutcome(old.getId(),"TRIGGERED","REJECTED","ENTRY_NOT_READY_NO_ORDER","Simulator waiting; no order submitted"));
        assertFalse(admission.prepare(order,()->{fail("No duplicate batch after rejection");return null;}).created());
        assertEquals(1,requests.claimIfStatusEqualsWithOutcome(old.getId(),"REJECTED","CANCELLED","ADX_BATCH_NOT_SUBMITTED","Another request in this signal failed"));
        var restarted=new AdxDmiEntryAdmission(em,requests,trades,runs,subs,manager,Clock.fixed(Instant.parse("2026-11-11T04:31:00Z"),AdxDmiRules.IST));
        assertFalse(restarted.prepare(order,()->{fail("No duplicate after cancellation/restart");return null;}).created());
        assertEquals(1,requests.count());
        assertEquals(order.getStrategyDecision(),requests.findAll().getFirst().getStrategyDecision());
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

    private TriggeredTradeSetupEntity filled(String signal,LocalDateTime entry) {
        return trades.saveAndFlush(TriggeredTradeSetupEntity.builder().appUserId(order.getUserId())
                .brokerCredentialsId(order.getBrokerCredentialsId()).symbol(order.getInstrument()).source(order.getSource())
                .strategyDecision("ADX_SIGNAL="+signal+";").status(TriggeredTradeStatus.EXITED_SUCCESS)
                .entryAt(entry).triggeredAt(entry.minusSeconds(1)).exitedAt(entry.plusMinutes(1)).actualEntryPrice(100.).build());
    }
    private void twoEarlierFilledSetups() {
        filled("2026-11-11T09:30:CE",LocalDateTime.of(2026,11,11,9,31));
        filled("2026-11-11T09:40:PE",LocalDateTime.of(2026,11,11,9,41));
    }
    @Test void thirdSetupIsBlockedAfterRestartEvenWhenPreviousPositionsAreClosed() {
        twoEarlierFilledSetups();
        var restarted=new AdxDmiEntryAdmission(em,requests,trades,runs,subs,manager,
                Clock.fixed(Instant.parse("2026-11-11T04:31:00Z"),AdxDmiRules.IST));
        var ex=assertThrows(IllegalStateException.class,()->restarted.prepareBatch(java.util.List.of(order,order,order),ignored->{fail("Daily cap must prevent persistence");return null;}));
        assertTrue(ex.getMessage().contains("daily limit"));assertEquals(0,requests.count());
    }
    @Test void threeClosedLegsCountOnceAndStillAllowThreeRequestsForTheSecondSetup() {
        for(int i=0;i<3;i++)filled("2026-11-11T09:30:CE",LocalDateTime.of(2026,11,11,9,31));
        var batch=admission.prepareBatch(java.util.List.of(order,order,order),ignored->save());
        assertTrue(batch.created());assertEquals(3,batch.requests().size());
    }
    @Test void noFillRejectionsAndCancellationsDoNotConsumeDailySlots() {
        for(var state:java.util.List.of(TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED)) {
            var request=save();request.setComment("ADX_SIGNAL=2026-11-11T09:"+(state==TriggeredTradeStatus.REJECTED?"30":"40")+":CE;");
            request.setStatus(state);requests.saveAndFlush(request);
            trades.saveAndFlush(TriggeredTradeSetupEntity.builder().appUserId(order.getUserId()).brokerCredentialsId(order.getBrokerCredentialsId())
                    .triggerRequestId(request.getId()).symbol("NIFTY").source(order.getSource()).status(state)
                    .triggeredAt(LocalDateTime.of(2026,11,11,9,31)).strategyDecision(request.getComment()).build());
        }
        assertTrue(admission.prepare(order,this::save).created());
    }
    @Test void partialBasketAndEvenRejectedFilledLegKeepTheirDailySlot() {
        twoEarlierFilledSetups();
        var last=trades.findAll().getLast();last.setStatus(TriggeredTradeStatus.REJECTED);trades.saveAndFlush(last);
        assertThrows(IllegalStateException.class,()->admission.prepare(order,this::save));
    }
    @Test void legacyConfirmedFillWithoutEntryTimestampUsesItsTriggeredDate() {
        twoEarlierFilledSetups();
        for(var trade:trades.findAll()){trade.setEntryAt(null);trades.saveAndFlush(trade);}
        assertThrows(IllegalStateException.class,()->admission.prepare(order,this::save));
    }
    @Test void previousDayAndTomorrowEntriesDoNotUseTodaysAllowance() {
        for(int day:java.util.List.of(10,12))for(int i=0;i<2;i++)
            filled("2026-11-"+day+"T09:"+(30+i*5)+":CE",LocalDateTime.of(2026,11,day,9,31+i*5));
        // Future rows have no exit date: they exercise date scoping without the existing overlap guard.
        for(var t:trades.findAll()){t.setExitedAt(null);trades.saveAndFlush(t);}
        assertTrue(admission.prepare(order,this::save).created());
    }
    @Test void anotherSymbolAccountUserOrStrategyHasItsOwnAllowance() {
        var a=filled("symbol-1",LocalDateTime.of(2026,11,11,9,31));
        var b=filled("symbol-2",LocalDateTime.of(2026,11,11,9,41));
        for(var t:java.util.List.of(a,b)){t.setSymbol("SENSEX");trades.saveAndFlush(t);}
        var other=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build());
        for(int i=0;i<2;i++) {
            var t=filled("account-"+i,LocalDateTime.of(2026,11,11,9,31+i));t.setBrokerCredentialsId(other.getId());trades.saveAndFlush(t);
            t=filled("user-"+i,LocalDateTime.of(2026,11,11,9,31+i));t.setAppUserId(9L);trades.saveAndFlush(t);
            t=filled("source-"+i,LocalDateTime.of(2026,11,11,9,31+i));t.setSource("strategy:OTHER");trades.saveAndFlush(t);
        }
        assertTrue(admission.prepare(order,this::save).created());
    }
    private TriggeredTradeSetupEntity candidateForPlacement() {
        runs.saveAndFlush(StrategyRun.builder().id("open").appUserId(order.getUserId()).brokerCredentialsId(order.getBrokerCredentialsId())
                .templateId(AdxDmiRules.TEMPLATE).symbol("NIFTY").tradingDay(LocalDate.of(2026,11,11)).instance("DAY")
                .status("OPEN").createdAt(LocalDateTime.of(2026,11,11,9,25)).build());
        return TriggeredTradeSetupEntity.builder().appUserId(order.getUserId()).brokerCredentialsId(order.getBrokerCredentialsId())
                .symbol("NIFTY").strategyRunId("open").strategyDecision(order.getStrategyDecision()).build();
    }
    @Test void alreadyPreparedThirdSetupCannotBypassTheCapAtPlacement() {
        twoEarlierFilledSetups();var candidate=candidateForPlacement();
        assertThrows(IllegalStateException.class,()->admission.place(candidate,()->{fail("No broker call after two filled setups");return null;}));
    }
    @Test void remainingLegsOfSecondFilledSetupCanStillPlace() {
        filled("2026-11-11T09:30:CE",LocalDateTime.of(2026,11,11,9,31));
        filled("2026-11-11T10:00:CE",LocalDateTime.of(2026,11,11,10,0));
        var candidate=candidateForPlacement();var result=org.com.sharekhan.dto.OrderPlacementResult.builder().success(true).build();
        assertSame(result,admission.place(candidate,()->result));
    }
    @Test void limitResetsOnTheNextIstTradingDateWithoutDeletingHistory() {
        twoEarlierFilledSetups();assertThrows(IllegalStateException.class,()->admission.prepare(order,this::save));
        admission=new AdxDmiEntryAdmission(em,requests,trades,runs,subs,manager,
                Clock.fixed(Instant.parse("2026-11-12T04:01:00Z"),AdxDmiRules.IST));
        order.setStrategyDecision("ADX_SIGNAL=2026-11-12T09:30:PE;ADX_DEADLINE=2026-11-12T09:35;");
        assertTrue(admission.prepare(order,this::save).created());assertEquals(2,trades.count());
    }
    @Test void bothAdxSourceVersionsAndCaseVariantsShareTheDailyCap() {
        twoEarlierFilledSetups();var rows=trades.findAll();
        rows.getFirst().setSource(AdxDmiRules.SOURCE.toLowerCase(java.util.Locale.ROOT));rows.getFirst().setSymbol("nifty");
        trades.saveAndFlush(rows.getFirst());
        assertThrows(IllegalStateException.class,()->admission.prepare(order,this::save));
    }
    @Test void concurrentDifferentSignalsCannotReserveTwoBasketsInTheLastAvailableSlot()throws Exception {
        filled("2026-11-11T09:30:CE",LocalDateTime.of(2026,11,11,9,31));
        var other=new TriggerRequest();org.springframework.beans.BeanUtils.copyProperties(order,other);
        other.setStrategyDecision("ADX_SIGNAL=2026-11-11T10:00:PE;ADX_DEADLINE=2026-11-11T10:05;");
        var start=new CountDownLatch(1);
        java.util.function.Function<TriggerRequest,Boolean> submit=candidate->{
            try {
                return admission.prepareBatch(java.util.List.of(candidate,candidate,candidate),input->requests.save(
                        TriggerTradeRequestEntity.builder().appUserId(input.getUserId()).brokerCredentialsId(input.getBrokerCredentialsId())
                                .symbol(input.getInstrument()).source(input.getSource()).strategyDecision(input.getStrategyDecision())
                                .createdAt(LocalDateTime.of(2026,11,11,10,1)).status(TriggeredTradeStatus.TRIGGERED).build())).created();
            } catch(IllegalStateException blocked) {
                assertTrue(blocked.getMessage().contains("unresolved"));return false;
            }
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return submit.apply(order);});
            var b=pool.submit(()->{start.await();return submit.apply(other);});start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(3,requests.count());
    }
}
