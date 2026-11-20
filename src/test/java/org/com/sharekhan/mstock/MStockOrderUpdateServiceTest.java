package org.com.sharekhan.mstock;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MStockOrderUpdateServiceTest {
    BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);
    CryptoService crypto=mock(CryptoService.class);
    MStockOrderSocketConnector connector=mock(MStockOrderSocketConnector.class);
    ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    MStockOrderUpdateService service=new MStockOrderUpdateService(brokers,trades,tokens,crypto,connector,events);
    List<WebSocket.Listener> listeners=new ArrayList<>();List<WebSocket> sockets=new ArrayList<>();
    Instant now=Instant.parse("2026-11-20T04:30:00Z");
    TriggeredTradeSetupEntity trade;
    AccessTokenEntity token;
    @BeforeEach void setup() {
        time(now);
        trade=TriggeredTradeSetupEntity.builder().id(88L).appUserId(2L).brokerCredentialsId(7L)
            .source("awr").orderId("12345").status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build();
        when(trades.findByStatusIn(anyList())).thenReturn(List.of(trade));
        when(brokers.findById(7L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(7L).appUserId(2L)
            .brokerName("MStock").apiKey("cipher").active(true).tradingEnabled(true).build()));
        token=AccessTokenEntity.builder().brokerName("MStock").brokerCredentialsId(7L).userId(2L).token("access-token").expiry(now.plusSeconds(3600)).build();
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("MStock",7L)).thenReturn(Optional.of(token));
        when(crypto.decrypt("cipher")).thenReturn("api-key");
        when(connector.connect(anyString(),anyString(),any())).thenAnswer(call->{
            listeners.add(call.getArgument(2));var socket=mock(WebSocket.class);sockets.add(socket);
            when(socket.sendText(any(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
            when(socket.sendPing(any())).thenReturn(CompletableFuture.completedFuture(socket));
            when(socket.sendPong(any())).thenReturn(CompletableFuture.completedFuture(socket));
            when(socket.sendClose(anyInt(),anyString())).thenReturn(CompletableFuture.completedFuture(socket));
            return CompletableFuture.completedFuture(socket);
        });
    }
    @AfterEach void close(){service.shutdown();}
    void time(Instant value){ReflectionTestUtils.setField(service,"clock",Clock.fixed(value,ZoneOffset.UTC));}
    void send(String value){listeners.getLast().onText(sockets.getLast(),value,true);}
    void drain() {
        try {((ExecutorService)ReflectionTestUtils.getField(service,"delivery")).submit(()->{}).get(1,TimeUnit.SECONDS);}
        catch (Exception failure) {throw new AssertionError(failure);}
    }
    void flushAndAwait(){service.flushHints();drain();verify(events,atLeastOnce()).publishEvent(isA(MStockOrderHintEvent.class));}

    @Test void enabledAccountsWithNoPlacedOrdersNeverConnectOrReadCredentials() {
        when(trades.findByStatusIn(anyList())).thenReturn(List.of());service.refresh();service.flushHints();
        verifyNoInteractions(connector,brokers,tokens,events);
    }
    @Test void unplacedOrInvalidHandlesDoNotCreateDemand() {
        for(String handle:new String[]{"","0","NA","null","MSTOCK-TAG-invalid"}) {
            trade.setOrderId(handle);service.refresh();
        }
        trade.setOrderId(null);service.refresh();verifyNoInteractions(connector,brokers,tokens);
    }
    @Test void oneConnectionPerAccountOnlyAfterOrderAndOnlyLoginIsSent() {
        var other=TriggeredTradeSetupEntity.builder().id(89L).appUserId(2L).brokerCredentialsId(7L)
            .orderId("12346").status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build();
        when(trades.findByStatusIn(anyList())).thenReturn(List.of(trade,other));
        service.refresh();service.refresh();verify(connector,times(1)).connect(eq("api-key"),eq("access-token"),any());
        verify(sockets.getFirst()).sendText("LOGIN:access-token",true);
        verify(sockets.getFirst(),times(1)).sendText(any(),anyBoolean());
        flushAndAwait();var hint=org.mockito.ArgumentCaptor.forClass(MStockOrderHintEvent.class);
        verify(events,atLeastOnce()).publishEvent(hint.capture());assertThat(hint.getValue().credentialId()).isEqualTo(7L);
    }
    @Test void pendingUnknownSubmissionIsDemandButKnownInactiveExitIsNot() {
        trade.setOrderId("MSTOCK-TAG-MS0123456789abcdefab");service.refresh();assertThat(sockets).hasSize(1);
        trade.setStatus(TriggeredTradeStatus.EXIT_FAILED);trade.setExitOrderId("12346");trade.setReason("EXIT_BROKER_ORDER_INACTIVE");
        service.refresh();verify(sockets.getFirst()).sendClose(eq(WebSocket.NORMAL_CLOSURE),anyString());
        service.refresh();assertThat(sockets).hasSize(1);
    }
    @Test void streamClosesWhenLastOrderCompletesAndNewExitOpensItAgain() {
        service.refresh();when(trades.findByStatusIn(anyList())).thenReturn(List.of());service.refresh();
        verify(sockets.getFirst()).sendClose(eq(1000),anyString());verify(sockets.getFirst()).abort();
        service.refresh();verify(connector,times(1)).connect(anyString(),anyString(),any());
        trade.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);trade.setExitOrderId("12346");
        when(trades.findByStatusIn(anyList())).thenReturn(List.of(trade));service.refresh();assertThat(sockets).hasSize(2);
    }
    @Test void ownershipTokenExpiryAndOtherBrokerCannotOpenConnection() {
        trade.setAppUserId(99L);service.refresh();trade.setAppUserId(2L);
        token.setUserId(99L);service.refresh();token.setUserId(2L);
        token.setExpiry(now);service.refresh();token.setExpiry(now.plusSeconds(3600));
        var broker=brokers.findById(7L).orElseThrow();broker.setBrokerName("Dhan");service.refresh();
        verifyNoInteractions(connector);
    }
    @Test void existingOrdersStillTrackWithNewTradingDisabled() {
        var account=brokers.findById(7L).orElseThrow();account.setTradingEnabled(false);account.setActive(false);
        service.refresh();assertThat(sockets).hasSize(1);
    }
    @Test void orderAndTradeMessagesAreBatchedAndDoNotTrustPayloadState() {
        service.refresh();flushAndAwait();clearInvocations(events);
        String first="{\"type\":\"order\",\"data\":{\"order_id\":\"12345\",\"status\":\"Complete\"}}";
        var listener=listeners.getFirst();listener.onText(sockets.getFirst(),first.substring(0,20),false);
        service.flushHints();verifyNoInteractions(events);
        listener.onText(sockets.getFirst(),first.substring(20),true);
        for(int i=0;i<100;i++)send("{\"type\":\"trade\",\"data\":{\"status\":\"Pending\"}}");
        service.flushHints();verify(events,timeout(1000).times(1)).publishEvent(new MStockOrderHintEvent(7L));
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }
    @Test void malformedBinaryAndMarketMessagesDoNotTriggerOrderReconciliation() {
        service.refresh();flushAndAwait();clearInvocations(events);
        send("invalid-json");send("{\"type\":\"quote\",\"data\":{}}");send("{\"type\":\"order\",\"data\":null}");
        listeners.getFirst().onBinary(sockets.getFirst(),ByteBuffer.wrap(new byte[]{1,2}),true);
        service.flushHints();verifyNoInteractions(events);
    }
    @Test void disconnectBackoffAndOldSocketMessagesCannotAffectNewSession() {
        service.refresh();var old=listeners.getFirst();var socket=sockets.getFirst();
        old.onClose(socket,1006,"secret-close-message");service.refresh();assertThat(sockets).hasSize(1);
        time(now.plusSeconds(2));service.refresh();assertThat(sockets).hasSize(2);flushAndAwait();clearInvocations(events);
        old.onText(socket,"{\"type\":\"order\",\"data\":{}}",true);old.onError(socket,new RuntimeException("sensitive uri"));
        service.flushHints();verifyNoInteractions(events);
    }
    @Test void rotatesChangedTokenAndClosesExpiredSessionWithoutAuthentication() {
        service.refresh();token.setToken("replacement");service.refresh();assertThat(sockets).hasSize(2);
        verify(sockets.getLast()).sendText("LOGIN:replacement",true);
        time(now.plusSeconds(3600));service.refresh();verify(sockets.getLast()).sendClose(eq(1000),anyString());
        verify(tokens,atLeastOnce()).findLatestByBrokerAndBrokerCredentialsId("MStock",7L);
    }
    @Test void pingAndPongMaintainConnectionAndMissingPongReconnects() {
        service.refresh();time(now.plusSeconds(31));service.refresh();verify(sockets.getFirst()).sendPing(any());
        listeners.getFirst().onPong(sockets.getFirst(),ByteBuffer.allocate(0));
        time(now.plusSeconds(100));service.refresh();assertThat(sockets).hasSize(1);
        time(now.plusSeconds(122));service.refresh();verify(sockets.getFirst()).sendClose(eq(1000),anyString());
        time(now.plusSeconds(124));service.refresh();assertThat(sockets).hasSize(2);
    }
    @Test void failedLoginAndOversizedFramesNeverLogOrPublishPayload() {
        service.refresh();send("x".repeat(262145));service.flushHints();
        verify(sockets.getFirst()).sendClose(eq(1000),anyString());
        verify(sockets.getFirst(),times(1)).sendText(any(),eq(true));
    }
    @Test void cancellationDuringConnectionAbortsLateSocketAndDoesNotLogin() {
        var pending=new CompletableFuture<WebSocket>();
        when(connector.connect(anyString(),anyString(),any())).thenAnswer(call->{listeners.add(call.getArgument(2));return pending;});
        service.refresh();when(trades.findByStatusIn(anyList())).thenReturn(List.of());service.refresh();
        assertThat(pending.isCancelled()).isTrue();
        var late=mock(WebSocket.class);listeners.getLast().onOpen(late);verify(late).abort();verify(late,never()).sendText(any(),anyBoolean());
    }
    @Test void failedAuthenticationUsesIncreasingBackoffWithoutRefreshingToken() {
        service.refresh();send("{\"type\":\"error\",\"data\":\"private-error\"}");
        time(now.plusSeconds(2));service.refresh();assertThat(sockets).hasSize(2);
        send("{\"type\":\"error\",\"data\":\"private-error\"}");
        time(now.plusSeconds(4));service.refresh();assertThat(sockets).hasSize(2);
        time(now.plusSeconds(6));service.refresh();assertThat(sockets).hasSize(3);
        verify(tokens,never()).findLatestByBroker(anyString());
    }
    @Test void disabledFeatureClosesExistingConnection() {
        service.refresh();ReflectionTestUtils.setField(service,"enabled",false);service.refresh();service.flushHints();
        verify(sockets.getFirst()).sendClose(eq(1000),anyString());verifyNoInteractions(events);
    }
    @Test void persistedPlacedOrderEventOpensConnectionPromptly() {
        service.onOrderPlaced(new OrderPlacedEvent(trade));drain();
        verify(connector,times(1)).connect(eq("api-key"),eq("access-token"),any());
    }
    @Test void orderPlacedEventRechecksDatabaseDemandRatherThanTrustingEventAlone() {
        when(trades.findByStatusIn(anyList())).thenReturn(List.of());service.onOrderPlaced(new OrderPlacedEvent(trade));
        verify(trades,timeout(1000)).findByStatusIn(anyList());verifyNoInteractions(connector);
    }
}
