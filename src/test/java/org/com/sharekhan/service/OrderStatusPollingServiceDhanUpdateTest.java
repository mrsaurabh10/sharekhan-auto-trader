package org.com.sharekhan.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.com.sharekhan.dhan.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.broker.*;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.ws.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderStatusPollingServiceDhanUpdateTest {
    TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    TradeExecutionService engine=mock(TradeExecutionService.class);
    BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);
    BrokerServiceFactory factory=mock(BrokerServiceFactory.class);
    DhanBrokerService broker=mock(DhanBrokerService.class);
    TelegramNotificationService notifications=mock(TelegramNotificationService.class);
    ScheduledExecutorService scheduler=mock(ScheduledExecutorService.class);
    List<Runnable> tasks=new ArrayList<>();List<ScheduledFuture<?>> futures=new ArrayList<>();
    OrderStatusPollingService service=new OrderStatusPollingService(trades,requests,mock(WebSocketClientService.class),engine,mock(WebSocketSubscriptionHelper.class),credentials,factory);
    TriggeredTradeSetupEntity trade;
    Instant morning=Instant.parse("2026-10-19T04:30:00Z");
    @BeforeEach void setup() {
        trade=TriggeredTradeSetupEntity.builder().id(88L).appUserId(4L).brokerCredentialsId(7L).symbol("TCS").exchange("NC")
            .brokerSecurityId("11536").quantity(10L).entryPrice(90d).orderId("entry-123").exitOrderId("12345").status(TriggeredTradeStatus.TARGET_ORDER_PLACED)
            .triggeredAt(LocalDateTime.of(2026,10,19,10,0)).exitOrderPlacedAt(LocalDateTime.of(2026,10,19,10,0)).build();
        when(trades.findById(88L)).thenReturn(Optional.of(trade));when(trades.save(any())).thenAnswer(c->c.getArgument(0));
        when(credentials.findById(7L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(7L).appUserId(4L).brokerName("Dhan").clientCode("enc-client").build()));
        CryptoService crypto=mock(CryptoService.class);when(crypto.decrypt("enc-client")).thenReturn("1000000001");
        ReflectionTestUtils.setField(service,"cryptoService",crypto);ReflectionTestUtils.setField(service,"telegramNotificationService",notifications);
        ReflectionTestUtils.setField(service,"executor",scheduler);setTime(morning);
        when(factory.getService("Dhan")).thenReturn(broker);
        when(broker.normalizeOrderUpdate(any(),any(),anyString(),any())).thenAnswer(c->c.getArgument(3));
        doAnswer(c->{((Runnable)c.getArgument(1)).run();return null;}).when(engine).reconcileEntryUnderLock(anyLong(),any(Runnable.class));
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any(TimeUnit.class))).thenAnswer(c->{
            tasks.add(c.getArgument(0));var future=mock(ScheduledFuture.class);futures.add(future);return future;
        });
        doAnswer(c->{((Runnable)c.getArgument(0)).run();return null;}).when(scheduler).execute(any(Runnable.class));
        ReflectionTestUtils.setField(service,"orderPollDelayMs",2000L);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.PENDING);
    }
    @AfterEach void close(){service.shutdown();}
    void setTime(Instant now){ReflectionTestUtils.setField(service,"monitorClock",Clock.fixed(now,ZoneOffset.UTC));}
    JSONObject row(String status){return new JSONObject().put("data",new JSONArray().put(new JSONObject().put("orderStatus",status)));}
    void event(JSONObject row){service.onDhanOrderUpdate(new DhanOrderUpdateEvent(7L,"12345","sat-test",row.toString()));}
    @ParameterizedTest @EnumSource(value=TriggeredTradeStatus.class,names={"TARGET_ORDER_PLACED","EXIT_ORDER_PLACED","EXIT_TRIGGERED"})
    void inactiveExitNeverRejectsOriginalEntryOrReleasesItsPriceFeed(TriggeredTradeStatus status) {
        trade.setStatus(status);service.monitorOrderStatus(trade);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.REJECTED);
        event(row("Rejected"));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_FAILED);assertThat(trade.getExitOrderId()).isEqualTo("12345");
        verify(engine,never()).syncLinkedEntryRequestStatus(any(),any());verify(engine,never()).releaseOptionFeedIfUnused(any(),any());
        if(!futures.isEmpty())verify(futures.getFirst()).cancel(false);verify(broker,never()).fetchOrderStatus(any(),any(),anyString());
    }
    @Test void sharedSharekhanTargetRejectionIsReconciledOnAckWithoutRecurringPolling() {
        var credential=credentials.findById(7L).orElseThrow();credential.setBrokerName("Sharekhan");credential.setCustomerId(123L);credential.setApiKey("encrypted-api");
        when(((CryptoService)ReflectionTestUtils.getField(service,"cryptoService")).decrypt("encrypted-api")).thenReturn("test-api");
        when(factory.getService("Sharekhan")).thenReturn(broker);
        service.monitorOrderStatus(trade);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.REJECTED);
        service.onBrokerHint(new org.com.sharekhan.monitoring.BrokerOrderHintEvent("Sharekhan","12345"));assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_FAILED);
        verify(engine,never()).syncLinkedEntryRequestStatus(any(),any());verify(engine,never()).releaseOptionFeedIfUnused(any(),any());
        assertThat(tasks).isEmpty();verify(broker,times(1)).fetchOrderStatus(any(),any(),eq("12345"));
    }
    @Test void realDhanNormalizationAndFillEvaluatorCloseTargetAtActualAverageWithoutRest() {
        var tokenStore=mock(org.com.sharekhan.auth.AccessTokenPersistenceService.class);
        when(tokenStore.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("test-token").expiry(Instant.now().plusSeconds(3600)).build()));
        var http=mock(DhanOrderClient.class);
        var adapter=new DhanBrokerService(http,mock(DhanInstrumentResolver.class),mock(DhanSubmissionClaim.class),credentials,tokenStore,(CryptoService)ReflectionTestUtils.getField(service,"cryptoService"));
        when(factory.getService("Dhan")).thenReturn(adapter);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenCallRealMethod();
        when(trades.markExitedWithPNL(anyLong(),any(),anyDouble(),any(),anyDouble(),any(),any())).thenReturn(1);
        service.monitorOrderStatus(trade);
        JSONObject update=new JSONObject().put("dhanClientId","1000000001").put("orderId","12345").put("orderStatus","TRADED")
            .put("securityId","11536").put("productType","CNC").put("exchangeSegment","NSE_EQ").put("transactionType","SELL").put("quantity",10).put("filledQty",10)
            .put("remainingQuantity",0).put("averageTradedPrice",100.5);
        event(update);
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXITED_SUCCESS);
        assertThat(trade.getExitPrice()).isEqualTo(100.5);assertThat(trade.getPnl()).isEqualTo(105d);
        verifyNoInteractions(http);verify(engine,times(1)).advanceStagedTargetStopsAfterTargetExit(any());
    }
    @Test void missedSocketFillIsPersistedFromRealDhanArrayThroughRestPolling() throws Exception {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        trade.setOrderId("12345");trade.setExitOrderId(null);
        var tokenStore=mock(org.com.sharekhan.auth.AccessTokenPersistenceService.class);
        when(tokenStore.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(
            AccessTokenEntity.builder().token("test-token").expiry(Instant.now().plusSeconds(3600)).build()));
        var http=mock(DhanOrderClient.class);
        var adapter=new DhanBrokerService(http,mock(DhanInstrumentResolver.class),mock(DhanSubmissionClaim.class),credentials,tokenStore,
            (CryptoService)ReflectionTestUtils.getField(service,"cryptoService"));
        when(factory.getService("Dhan")).thenReturn(adapter);
        JSONObject fill=new JSONObject().put("dhanClientId","1000000001").put("orderId","12345").put("orderStatus","TRADED")
            .put("securityId","11536").put("productType","CNC").put("exchangeSegment","NSE_EQ").put("transactionType","BUY").put("quantity",10).put("filledQty",10)
            .put("remainingQuantity",0).put("averageTradedPrice",100.5);
        when(http.exchange(eq("GET"),eq("/orders/12345"),eq("test-token"),isNull()))
            .thenReturn(new DhanOrderClient.Response(200,new JSONArray().put(fill).toString()));
        when(engine.evaluateOrderFinalStatus(any(),any())).thenCallRealMethod();
        service.monitorOrderStatus(trade);
        tasks.getFirst().run();
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        assertThat(trade.getActualEntryPrice()).isEqualTo(100.5);
        assertThat(trade.getEntryAt()).isNotNull();
        verify(trades).save(argThat(saved->saved.getStatus()==TriggeredTradeStatus.EXECUTED && Double.valueOf(100.5).equals(saved.getActualEntryPrice())));
        verify(engine,times(1)).handleEntryOrderExecution(trade);
        verify(futures.getFirst()).cancel(false);
        tasks.getFirst().run();
        verify(engine,times(1)).handleEntryOrderExecution(trade);
        verify(http,times(1)).exchange(eq("GET"),eq("/orders/12345"),eq("test-token"),isNull());
        verify(http,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void streamRejectsForeignCredentialAndUnmatchedOrderBeforeReconciliation() {
        service.monitorOrderStatus(trade);
        service.onDhanOrderUpdate(new DhanOrderUpdateEvent(8L,"12345","sat-test",row("Pending").toString()));
        service.onDhanOrderUpdate(new DhanOrderUpdateEvent(7L,"foreign-order","foreign-correlation",row("Pending").toString()));
        verify(engine,never()).evaluateOrderFinalStatus(any(),any());verifyNoInteractions(broker);
    }
    @Test void correlationHandleReceivesSocketUpdateAndEntryHookCanImmediatelyStartTargetMonitoring() {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);trade.setOrderId("DHAN-CORR-sat-test");trade.setExitOrderId(null);
        service.monitorOrderStatus(trade);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        doAnswer(c->{trade.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);trade.setExitOrderId("12345");service.monitorOrderStatus(trade);return null;}).when(engine).handleEntryOrderExecution(any());
        event(row("Fully Executed"));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.TARGET_ORDER_PLACED);assertThat(tasks).hasSize(1);
        verify(futures.getFirst()).cancel(false);
        verify(engine,times(1)).handleEntryOrderExecution(any());
    }
    @Test void repeatedUnavailableRestBacksOffAndAlertsOnce() {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);trade.setOrderId("12345");trade.setExitOrderId(null);
        ReflectionTestUtils.setField(service,"dhanUnavailableLimit",2);service.monitorOrderStatus(trade);
        when(engine.evaluateOrderFinalStatus(any(),isNull())).thenReturn(TradeExecutionService.TradeStatus.NO_RECORDS);
        tasks.getFirst().run();setTime(morning.plusSeconds(60));tasks.getFirst().run();
        assertThat(trade.getReason()).isEqualTo("DHAN_ORDER_RECONCILIATION_REQUIRED");
        clearInvocations(trades);setTime(morning.plusSeconds(120));tasks.getFirst().run();verify(trades,never()).findById(anyLong());verify(broker,times(2)).fetchOrderStatus(any(),any(),eq("12345"));
        setTime(morning.plusSeconds(360));tasks.getFirst().run();verify(broker,times(3)).fetchOrderStatus(any(),any(),eq("12345"));
        verify(notifications,times(1)).sendTradeMessageForUser(eq(4L),eq("Dhan order needs reconciliation"),anyString());
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }
    @Test void finalSessionReconciliationStopsTimerAndStillAcceptsConfirmedStreamFill() {
        setTime(Instant.parse("2026-10-19T10:16:00Z"));service.monitorOrderStatus(trade);service.onDhanStreamConnected(new DhanOrderStreamConnectedEvent(7L));
        assertThat(trade.getReason()).isEqualTo("DHAN_SESSION_RECONCILIATION_REQUIRED");assertThat(tasks).isEmpty();
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        event(row("Fully Executed"));assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXITED_SUCCESS);
        verify(engine,times(1)).advanceStagedTargetStopsAfterTargetExit(any());
    }
    @Test void confirmedInactiveOrderAtFinalDeadlineIsNotRelabelledAsUnknown() {
        setTime(Instant.parse("2026-10-19T10:16:00Z"));
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.REJECTED);
        service.monitorOrderStatus(trade);service.onDhanStreamConnected(new DhanOrderStreamConnectedEvent(7L));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_FAILED);
        assertThat(trade.getReason()).isEqualTo("EXIT_BROKER_ORDER_INACTIVE");
        verify(notifications,never()).sendTradeMessageForUser(eq(4L),eq("Dhan order needs reconciliation"),anyString());
    }
    @Test void suspendedSessionOrPartialExitDoesNotResumeFrequentPollingAfterRestart() {
        trade.setReason("DHAN_SESSION_RECONCILIATION_REQUIRED");service.monitorOrderStatus(trade);assertThat(tasks).isEmpty();
        service.shutdown();
    }
    @Test void partialInactiveExitRetainsOriginalOrderAndStopsTimerWithAttention() {
        service.monitorOrderStatus(trade);
        event(new JSONObject().put("requiresExitReconciliation",true).put("filledQuantity",3).put("remainingQuantity",7));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_FAILED);assertThat(trade.getExitOrderId()).isEqualTo("12345");
        assertThat(trade.getQuantity()).isEqualTo(10);assertThat(trade.getReason()).isEqualTo("DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED");
        assertThat(tasks).isEmpty();verify(engine,never()).evaluateOrderFinalStatus(any(),any());
    }
    @Test void finalSessionTransportExceptionAlsoSuspendsAndReconnectForcesCatchupBeforeDeadline() {
        service.monitorOrderStatus(trade);
        service.onDhanStreamConnected(new DhanOrderStreamConnectedEvent(7L));
        verify(broker,times(1)).fetchOrderStatus(any(),any(),anyString());
        setTime(Instant.parse("2026-10-19T10:16:00Z"));when(broker.fetchOrderStatus(any(),any(),anyString())).thenThrow(new IllegalStateException("transport unavailable"));
        service.onDhanStreamConnected(new DhanOrderStreamConnectedEvent(7L));assertThat(tasks).isEmpty();assertThat(trade.getReason()).isEqualTo("DHAN_SESSION_RECONCILIATION_REQUIRED");
    }
    @Test void restingTargetAllocatesNoTimerAndUnrelatedUpdatesDoNotReadDatabase() {
        service.monitorOrderStatus(trade);service.monitorOrderStatus(trade);
        assertThat(tasks).isEmpty();clearInvocations(trades);
        service.onDhanOrderUpdate(new DhanOrderUpdateEvent(7L,"manual-order","manual",row("Pending").toString()));
        verifyNoInteractions(trades,broker);
    }
    @Test void entryPollsAtConfiguredCadenceAndChaseSnapshotsAvoidDuplicateRest() {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);trade.setOrderId("12345");trade.setExitOrderId(null);
        service.monitorOrderStatus(trade);
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class),eq(250L),eq(2000L),eq(TimeUnit.MILLISECONDS));
        when(engine.isEntryOrderChaseActive(88L)).thenReturn(true);
        tasks.getFirst().run();verifyNoInteractions(broker);
        when(engine.evaluateOrderFinalStatus(any(),any())).thenReturn(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        service.onSnapshot(new org.com.sharekhan.monitoring.BrokerOrderSnapshotEvent(88L,7L,"12345",row("Fully Executed").toString()));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verify(engine).handleEntryOrderExecution(any());verifyNoInteractions(broker);verify(futures.getFirst()).cancel(false);
    }
    @Test void stoppedEntryChaseHandsConfirmationBackToPoller() {
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);trade.setOrderId("12345");trade.setExitOrderId(null);service.monitorOrderStatus(trade);
        when(engine.isEntryOrderChaseActive(88L)).thenReturn(true);tasks.getFirst().run();
        when(engine.isEntryOrderChaseActive(88L)).thenReturn(false);tasks.getFirst().run();
        verify(broker,times(1)).fetchOrderStatus(any(),any(),eq("12345"));
    }
    @Test void activeExitChaseOwnsRestAndStoppingItAllowsOnlyOneFinalCheck() {
        trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);service.monitorOrderStatus(trade);
        when(engine.isExitOrderChaseActive(88L)).thenReturn(true);tasks.getFirst().run();verifyNoInteractions(broker);
        when(engine.isExitOrderChaseActive(88L)).thenReturn(false);tasks.getFirst().run();
        verify(broker,times(1)).fetchOrderStatus(any(),any(),eq("12345"));verify(futures.getFirst()).cancel(false);
    }
    @Test void staleSnapshotCannotCompleteReplacementTargetAndStatusChangeReplacesMode() {
        service.monitorOrderStatus(trade);trade.setExitOrderId("replacement");trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        service.monitorOrderStatus(trade);assertThat(tasks).hasSize(1);
        service.onSnapshot(new org.com.sharekhan.monitoring.BrokerOrderSnapshotEvent(88L,7L,"12345",row("Fully Executed").toString()));
        service.onSnapshot(new org.com.sharekhan.monitoring.BrokerOrderSnapshotEvent(88L,8L,"replacement",row("Fully Executed").toString()));
        event(row("Fully Executed"));verify(engine,never()).evaluateOrderFinalStatus(any(),any());
    }

    @Test void cancelledOldExitCallbackCannotCancelReplacementMonitor() {
        trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);service.monitorOrderStatus(trade);
        trade.setExitOrderId("replacement");service.monitorOrderStatus(trade);
        tasks.getFirst().run();verify(futures.getLast(),never()).cancel(anyBoolean());verifyNoInteractions(broker);
        tasks.getLast().run();verify(broker).fetchOrderStatus(any(),any(),eq("replacement"));
    }

}
