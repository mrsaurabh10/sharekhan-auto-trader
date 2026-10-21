package org.com.sharekhan.service;

import org.com.sharekhan.dhan.DhanSubmissionClaim;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","logging.level.org.hibernate.SQL=OFF"},showSql=false)
@Import({ExitChaseControlService.class,BrokerExitSubmissionStore.class,DhanSubmissionClaim.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ExitSafetyPersistenceTest {
    @Autowired ExitChaseControlService controls;
    @Autowired ExitChaseControlRepository controlRows;
    @Autowired BrokerExitSubmissionStore submissions;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired DhanSubmissionClaim dhan;
    @Autowired PlatformTransactionManager transactions;
    TriggeredTradeSetupEntity trade;
    Instant start=Instant.parse("2026-10-21T04:00:00Z");
    @BeforeEach void setup() {
        trade=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().appUserId(4L).brokerCredentialsId(7L)
            .symbol("NIFTY").quantity(65L).status(TriggeredTradeStatus.EXIT_ORDER_PLACED)
            .exitOrderId("ORDER-"+System.nanoTime()).exitReason("TARGET_HIT").comment("Prior history retained").build());
    }
    @Test void restoredBudgetDoesNotMoveStartAndCompletedBudgetCannotRestart() {
        controls.begin(trade,start);
        assertThat(controls.begin(trade,start.plusSeconds(200)).getStartedAt()).isEqualTo(start);
        controls.complete(trade.getId(),trade.getExitOrderId());
        assertThat(controls.begin(trade,start.plusSeconds(500)).isActive()).isFalse();
        assertThat(controlRows.findById(trade.getId()).orElseThrow().getStartedAt()).isEqualTo(start);
    }
    @Test void protectiveIntentTransitionStartsOneNewBudgetAndThenPreservesIt() {
        controls.begin(trade,start);trade.setExitReason("STOP_LOSS_HIT");
        assertThat(controls.begin(trade,start.plusSeconds(1000)).getStartedAt()).isEqualTo(start.plusSeconds(1000));
        assertThat(controls.begin(trade,start.plusSeconds(1200)).getStartedAt()).isEqualTo(start.plusSeconds(1000));
    }
    @Test void failedAlertRemainsDurableAndRetryIsBoundedWithHistoryPreserved() {
        controls.attention(trade,"Exit unresolved",start);
        assertThat(trades.findById(trade.getId()).orElseThrow().getReason()).isEqualTo("EXIT_RECONCILIATION_REQUIRED");
        assertThat(trades.findById(trade.getId()).orElseThrow().getComment()).contains("Prior history retained","Exit unresolved");
        assertThat(controls.dueAlerts(start)).hasSize(1);
        assertThat(controls.dueAlerts(start.plusSeconds(1))).isEmpty();
        assertThat(controls.dueAlerts(start.plusSeconds(61))).hasSize(1);
        controls.delivered(trade.getId(),"Exit unresolved");
        assertThat(controls.dueAlerts(start.plusSeconds(7200))).isEmpty();
    }
    @Test void sendClaimSurvivesCallerRollbackAndAdvancesOnlyAfterMatchingInactiveOrder() {
        String[] handle={null};
        new TransactionTemplate(transactions).execute(status->{
            var claim=submissions.claim(trade.getId(),7L);assertThat(claim.send()).isTrue();handle[0]=claim.handle();
            status.setRollbackOnly();return null;
        });
        assertThat(submissions.claim(trade.getId(),7L).send()).isFalse();
        submissions.accepted(handle[0],"BROKER-1");
        submissions.inactive(trade.getId(),7L,"OTHER");
        assertThat(submissions.claim(trade.getId(),7L).send()).isFalse();
        submissions.inactive(trade.getId(),7L,"BROKER-1");
        var next=submissions.claim(trade.getId(),7L);assertThat(next.send()).isTrue();assertThat(next.handle()).endsWith("-2");
    }
    @Test void simultaneousSharekhanCallersHaveOneCommittedSender() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        Callable<Boolean> task=()->{go.await();try{return submissions.claim(trade.getId(),7L).send();}catch(org.springframework.dao.DataIntegrityViolationException duplicate){return false;}};
        try {
            var a=pool.submit(task);var b=pool.submit(task);go.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void legacyDhanClaimAndVersionedGenerationsRemainDiscoverable() {
        String suffix=String.valueOf(trade.getId());
        dhan.claim("sat-legacy-"+suffix,7L,"TRADE:"+suffix,"SELL");
        assertThat(dhan.latestExit(7L,trade.getId()).orElseThrow().generation()).isZero();
        dhan.exitOutcome("sat-legacy-"+suffix,"INACTIVE_ZERO_FILL");
        dhan.claimExit("sat-next-"+suffix,7L,"TRADE:"+suffix+":EXIT:1",1);
        assertThat(dhan.latestExit(7L,trade.getId()).orElseThrow().generation()).isEqualTo(1);
        assertThatThrownBy(()->dhan.claimExit("sat-next-"+suffix,7L,"TRADE:"+suffix+":EXIT:1",1))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void dhanRefusedSubmissionThenRetryPersistsDistinctGenerationsBeforeEachPost() throws Exception {
        var http=org.mockito.Mockito.mock(org.com.sharekhan.dhan.DhanOrderClient.class);
        var instruments=org.mockito.Mockito.mock(org.com.sharekhan.dhan.DhanInstrumentResolver.class);
        var credentials=org.mockito.Mockito.mock(BrokerCredentialsRepository.class);
        var tokens=org.mockito.Mockito.mock(org.com.sharekhan.auth.AccessTokenPersistenceService.class);
        var crypto=org.mockito.Mockito.mock(org.com.sharekhan.util.CryptoService.class);
        trade.setExitOrderId(null);trade.setExchange("NF");trade.setIntraday(false);
        org.mockito.Mockito.when(credentials.findById(7L)).thenReturn(java.util.Optional.of(BrokerCredentialsEntity.builder()
            .id(7L).appUserId(4L).brokerName("Dhan").clientCode("enc").build()));
        org.mockito.Mockito.when(crypto.decrypt("enc")).thenReturn("1000000001");
        org.mockito.Mockito.when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(java.util.Optional.of(
            AccessTokenEntity.builder().token("test-token").expiry(Instant.now().plusSeconds(3600)).build()));
        org.mockito.Mockito.when(instruments.resolve(trade)).thenReturn(new org.com.sharekhan.dhan.DhanInstrumentResolver.Instrument(
            "NSE_FNO","51346",65,new java.math.BigDecimal("0.05"),1800));
        var position=new org.json.JSONObject().put("dhanClientId","1000000001").put("exchangeSegment","NSE_FNO")
            .put("securityId","51346").put("productType","MARGIN").put("positionType","LONG").put("netQty",65);
        org.mockito.Mockito.when(http.exchange("GET","/positions","test-token",null)).thenReturn(
            new org.com.sharekhan.dhan.DhanOrderClient.Response(200,new org.json.JSONArray().put(position).toString()));
        org.mockito.Mockito.when(http.exchange("GET","/orders","test-token",null)).thenReturn(
            new org.com.sharekhan.dhan.DhanOrderClient.Response(200,"[]"));
        org.mockito.Mockito.when(http.exchange(org.mockito.ArgumentMatchers.eq("POST"),org.mockito.ArgumentMatchers.eq("/orders"),org.mockito.ArgumentMatchers.eq("test-token"),org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.com.sharekhan.dhan.DhanOrderClient.Response(400,"{}"),
                new org.com.sharekhan.dhan.DhanOrderClient.Response(200,"{\"orderId\":\"EXIT-2\",\"orderStatus\":\"PENDING\"}"));
        var adapter=new org.com.sharekhan.service.broker.DhanBrokerService(http,instruments,dhan,credentials,tokens,crypto);
        var context=new org.com.sharekhan.dto.BrokerContext(null,null,"1000000001","Dhan",7L);
        assertThat(adapter.placeExitOrder(trade,context,250).isSuccess()).isFalse();
        assertThat(dhan.latestExit(7L,trade.getId()).orElseThrow().outcome()).isEqualTo("REFUSED");
        assertThat(adapter.placeExitOrder(trade,context,250).getOrderId()).isEqualTo("EXIT-2");
        assertThat(dhan.latestExit(7L,trade.getId()).orElseThrow().generation()).isEqualTo(2);
        org.mockito.Mockito.verify(http,org.mockito.Mockito.times(2)).exchange(org.mockito.ArgumentMatchers.eq("POST"),org.mockito.ArgumentMatchers.eq("/orders"),org.mockito.ArgumentMatchers.eq("test-token"),org.mockito.ArgumentMatchers.any());
    }

    @Test void alertSurvivesTradeRollbackWithoutAnIndependentWriteWaitingOnTheCallerRow() {
        new TransactionTemplate(transactions).execute(status->{
            var locked=trades.findById(trade.getId()).orElseThrow();
            locked.setComment("Uncommitted caller update");trades.saveAndFlush(locked);
            controls.attention(locked,"Durable despite caller rollback",start);
            status.setRollbackOnly();return null;
        });
        assertThat(controlRows.findById(trade.getId()).orElseThrow().getAttention()).isEqualTo("Durable despite caller rollback");
        assertThat(trades.findById(trade.getId()).orElseThrow().getComment()).isEqualTo("Prior history retained");
        assertThat(controls.dueAlerts(start).stream().anyMatch(c->trade.getId().equals(c.getTradeId()))).isTrue();
    }

}
