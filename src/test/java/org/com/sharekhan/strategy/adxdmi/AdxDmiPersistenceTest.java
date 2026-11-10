package org.com.sharekhan.strategy.adxdmi;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeAuditService;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties="spring.jpa.show-sql=false",showSql=false)
@Import(AdxDmiPersistenceTest.Beans.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AdxDmiPersistenceTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,11,9,10,1);
    static final Instant AT=NOW.atZone(AdxDmiRules.IST).toInstant();
    @TestConfiguration static class Beans {
        @Bean AdxDmiPaperBroker paper(EntityManager em,TriggeredTradeSetupRepository t,TriggerTradeRequestRepository r,
                                                QuoteCacheService q,StrategyRunCoordinator runs,TradeAuditService audit){
            return new AdxDmiPaperBroker(em,t,r,q,runs,audit,Clock.fixed(AT,AdxDmiRules.IST));
        }
    }
    @MockBean QuoteCacheService quotes;
    @MockBean StrategyRunCoordinator runs;
    @MockBean TradeAuditService audit;
    @Autowired AdxDmiPaperBroker paper;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired TriggerTradeRequestRepository requests;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired StrategyRunRepository runRepository;
    StrategyApplyRequest request;
    final ScriptMasterEntity option=AdxDmiStrategyTest.option(123,"CE","10/11/2026",23000);
    final AdxDmiRules.Signal signal=new AdxDmiRules.Signal(1,NOW.withMinute(0),23001,22980,new AdxDmiRules.Reading(30,10,30));
    @BeforeEach void setup(){
        trades.deleteAll();requests.deleteAll();brokers.deleteAll();runRepository.deleteAll();
        var b=brokers.saveAndFlush(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").active(true).build());
        request=new StrategyApplyRequest();request.setUserId(1L);request.setBrokerCredentialsId(b.getId());request.setLots(3);request.setSymbol("NIFTY");
        runRepository.saveAndFlush(StrategyRun.builder().id("paper-test-run").appUserId(1L).brokerCredentialsId(b.getId())
                .templateId(AdxDmiRules.TEMPLATE).symbol("NIFTY").tradingDay(NOW.toLocalDate()).instance("DAY").lots(3).status("OPEN").createdAt(NOW).build());
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(23002)));
        when(quotes.getSnapshot(123)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(123).bestBid(100d).bestAsk(101d)
                .bidQuantity(65d).askQuantity(195d).coherentBook(true).lastBookAt(AT).bookSource("SHAREKHAN_WS").bookExchange("NF").bookProviderIdentity("scrip:123").build()));
        doAnswer(c->{((TriggerRequest)c.getArgument(0)).setStrategyRunId("paper-test-run");return null;}).when(runs).attach(any());
    }
    QuoteCacheService.QuoteSnapshot spot(double price){return QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(price)
            .lastLtpAt(AT).ltpSource("SHAREKHAN_WS").ltpExchange("NC").ltpProviderIdentity("scrip:20000").build();}
    @Test void restartRetainsSignalDedupAndChargeAwareBidExit(){
        var req=paper.open(request,signal,option,20000,List.of(option));
        var t=trades.findByTriggerRequestId(req.getId()).getFirst();
        assertEquals(101,t.getActualEntryPrice());assertEquals(23002,t.getEntryPrice());assertEquals(23024,t.getTarget1());
        when(quotes.getSnapshot(20000)).thenReturn(Optional.of(spot(22979)));paper.evaluate(t.getId());
        var closed=trades.findById(t.getId()).orElseThrow();assertEquals(100,closed.getExitPrice());assertTrue(closed.getEffectivePnl()<closed.getPnl());
        assertEquals("ADX_DMI_STOP",closed.getExitReason());
        assertThrows(IllegalStateException.class,()->paper.open(request,signal,option,20000,List.of(option)));
        assertEquals(3,trades.count());assertEquals(1,requests.count());
    }
    @Test void concurrentEvaluationsCannotOpenTwoPositionsOnTheSameBroker() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> work=()->{
                var r=new StrategyApplyRequest();r.setUserId(1L);r.setBrokerCredentialsId(request.getBrokerCredentialsId());r.setLots(3);r.setSymbol("NIFTY");
                start.await();try{paper.open(r,signal,option,20000,List.of(option));return true;}catch(IllegalStateException blocked){return false;}
            };
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(3,trades.count());assertEquals(1,requests.count());
    }
}
