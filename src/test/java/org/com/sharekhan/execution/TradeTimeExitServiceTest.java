package org.com.sharekhan.execution;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.cache.LtpCacheService;
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
@Import({TradeTimeExitService.class,LtpCacheService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TradeTimeExitServiceTest {
    static final LocalDateTime START=LocalDateTime.of(2026,11,12,10,0);
    @Autowired TradeTimeExitService timer;
    @Autowired LtpCacheService prices;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    List<TriggeredTradeSetupEntity> group;
    @BeforeEach void setup() {
        trades.deleteAll();brokers.deleteAll();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->em.createQuery("delete from TradeTimeExitState").executeUpdate());
        var b=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(3L).brokerName("Simulator").active(true).build());
        group=new ArrayList<>();String key=UUID.randomUUID().toString();
        for(int leg=1;leg<=3;leg++) {
            var c=new TradeExecutionSettings();c.setTimeExitMinutes(15);c.setTimeExitProgressPrice(110d);
            c.setTimeExitProgressDirection(TradeExecutionSettings.Direction.ABOVE);c.setTimeExitUseSpot(true);c.setTimeExitGroupId(key);
            group.add(trades.saveAndFlush(TriggeredTradeSetupEntity.builder().appUserId(3L).brokerCredentialsId(b.getId())
                .symbol("ANY_SYMBOL").scripCode(123).spotScripCode(456).status(TriggeredTradeStatus.EXECUTED)
                .entryAt(START.plusSeconds(leg-1)).optionType("CE").stopLoss(90d).target1(100d+10*leg).executionSettings(c).build()));
        }
    }
    boolean observe(int leg,Double price,LocalDateTime now){return timer.observe(group.get(leg).getId(),price,now,now);}
    @Test void firstFillStartsSharedTimerWithoutRunAndMissingQuotesCannotPauseIt() {
        assertFalse(observe(2,null,START.plusMinutes(14).plusSeconds(59)));
        assertTrue(observe(2,null,START.plusMinutes(15)));
        for(var t:group){var saved=trades.findById(t.getId()).orElseThrow();assertEquals("TIME_EXIT",saved.getExitReason());assertEquals(TriggeredTradeStatus.EXIT_TRIGGERED,saved.getStatus());assertEquals(90d,saved.getStopLoss());}
        assertTrue(observe(0,140d,START.plusMinutes(20))); // Restart/new transaction cannot reset intent.
    }
    @Test void t1ObservationSurvivesClosedFirstLegAndRetracementAcrossTransactions() {
        assertFalse(observe(0,110d,START.plusMinutes(1)));
        var first=trades.findById(group.getFirst().getId()).orElseThrow();first.setStatus(TriggeredTradeStatus.COMPLETED);trades.saveAndFlush(first);
        assertFalse(observe(1,100d,START.plusMinutes(20)));assertFalse(observe(2,100d,START.plusMinutes(20)));
    }
    @Test void exactDeadlineProgressDoesNotCancelTimeout() {
        assertTrue(observe(0,110d,START.plusMinutes(15)));
        assertTrue(observe(1,140d,START.plusMinutes(16)));
    }
    @Test void staleFutureOrPreEntryObservationsDoNotCancelTimeout() {
        assertFalse(timer.observe(group.get(0).getId(),140d,START.minusSeconds(1),START.plusMinutes(1)));
        assertFalse(timer.observe(group.get(1).getId(),140d,START.plusMinutes(14).plusSeconds(59),START.plusMinutes(14)));
        assertTrue(timer.observe(group.get(2).getId(),140d,START.plusMinutes(14),START.plusMinutes(15)));
    }
    @Test void progressTickReceivedJustBeforeDeadlineCountsAtNextPoll() {
        assertFalse(timer.observe(group.get(0).getId(),110d,START.plusMinutes(14).plusSeconds(59),START.plusMinutes(15)));
    }
    @Test void belowProgressSupportsPutsWithoutInspectingSymbolOrOptionType() {
        for(var t:group){t.getExecutionSettings().setTimeExitProgressDirection(TradeExecutionSettings.Direction.BELOW);t.getExecutionSettings().setTimeExitProgressPrice(90d);trades.saveAndFlush(t);}
        assertFalse(observe(1,90d,START.plusMinutes(2)));assertFalse(observe(2,100d,START.plusMinutes(20)));
    }
    @Test void unconditionalMaximumHoldDoesNotCancelForPriceMovement() {
        for(var t:group){t.getExecutionSettings().setTimeExitProgressPrice(null);t.getExecutionSettings().setTimeExitProgressDirection(null);trades.saveAndFlush(t);}
        assertFalse(observe(0,1000d,START.plusMinutes(2)));assertTrue(observe(2,1000d,START.plusMinutes(15)));
    }
    @Test void priorStopClaimIsPreservedWhileOtherLegsTimeOut() {
        var t=group.getFirst();t.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);t.setExitReason("STOP_LOSS_HIT");trades.saveAndFlush(t);
        assertTrue(observe(1,null,START.plusMinutes(15)));
        assertFalse(observe(0,null,START.plusMinutes(16)));assertEquals("STOP_LOSS_HIT",trades.findById(t.getId()).orElseThrow().getExitReason());
    }
    @Test void sameGroupNameInAnotherExecutionAccountIsIsolated() {
        var other=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(3L).brokerName("AnotherBroker").active(true).build());
        var t=group.getFirst();t.setId(null);t.setBrokerCredentialsId(other.getId());t=trades.saveAndFlush(t);
        assertFalse(observe(1,110d,START.plusMinutes(1)));
        assertTrue(timer.observe(t.getId(),null,null,START.plusMinutes(15)));
        assertFalse(observe(2,100d,START.plusMinutes(20)));
    }
    @Test void failedTimedExitRemainsRetryableWithoutResettingItsClock() {
        assertTrue(observe(0,null,START.plusMinutes(15)));
        var t=trades.findById(group.getFirst().getId()).orElseThrow();t.setStatus(TriggeredTradeStatus.EXIT_FAILED);trades.saveAndFlush(t);
        assertTrue(observe(0,140d,START.plusMinutes(20)));
        assertEquals(TriggeredTradeStatus.EXIT_TRIGGERED,trades.findById(t.getId()).orElseThrow().getStatus());
    }
    @Test void cacheProgressUsesConfiguredUnderlyingInsteadOfOptionPremium() {
        var start=LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(1);
        for(var t:group){t.setEntryAt(start);trades.saveAndFlush(t);}
        prices.updateLtp(123,140d);prices.updateLtp(456,100d);
        assertFalse(timer.observeFromCache(group.getFirst().getId()));
        assertTrue(observe(1,null,start.plusMinutes(15)));
    }
    @Test void cacheProgressCanUseTradedPriceWithoutSpotOrStrategyOwnership() {
        var start=LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(1);
        for(var t:group){t.setEntryAt(start);t.getExecutionSettings().setTimeExitUseSpot(false);trades.saveAndFlush(t);}
        prices.updateLtp(123,140d);prices.updateLtp(456,100d);
        assertFalse(timer.observeFromCache(group.getFirst().getId()));
        assertFalse(observe(1,null,start.plusMinutes(15)));
    }
    @Test void concurrentLegsCreateOnlyOneState() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return observe(0,null,START.plusMinutes(15));});
            var b=pool.submit(()->{gate.await();return observe(1,null,START.plusMinutes(15));});
            gate.countDown();assertTrue(a.get(15,TimeUnit.SECONDS));assertTrue(b.get(15,TimeUnit.SECONDS));
        }
        var count=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->em.createQuery("select count(s) from TradeTimeExitState s",Long.class).getSingleResult());assertEquals(1L,count);
    }
}
