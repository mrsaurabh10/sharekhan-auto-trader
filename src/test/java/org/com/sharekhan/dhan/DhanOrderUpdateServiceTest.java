package org.com.sharekhan.dhan;

import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DhanOrderUpdateServiceTest {
    BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);
    CryptoService crypto=mock(CryptoService.class);
    DhanOrderSocketConnector connector=mock(DhanOrderSocketConnector.class);
    ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    DhanOrderUpdateService service=new DhanOrderUpdateService(brokers,trades,tokens,crypto,connector,events);
    List<WebSocket.Listener> listeners=new ArrayList<>();List<WebSocket> sockets=new ArrayList<>();
    Instant now=Instant.parse("2026-10-19T04:30:00Z");
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now,ZoneOffset.UTC));
        when(trades.findByStatusIn(any())).thenReturn(List.of());
        when(brokers.findAllByBrokerName("Dhan")).thenReturn(List.of(BrokerCredentialsEntity.builder().id(7L).appUserId(4L).brokerName("Dhan").clientCode("enc-client").active(true).tradingEnabled(true).build()));
        when(crypto.decrypt("enc-client")).thenReturn("1000000001");
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("test-token").expiry(now.plusSeconds(3600)).build()));
        when(connector.connect(any())).thenAnswer(call->{
            listeners.add(call.getArgument(0));var socket=mock(WebSocket.class);sockets.add(socket);
            when(socket.sendText(anyString(),eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
            when(socket.sendPing(any())).thenReturn(CompletableFuture.completedFuture(socket));
            return CompletableFuture.completedFuture(socket);
        });
    }
    @AfterEach void close(){service.shutdown();}
    String alert(String status,long filled,long remaining,String time) {
        return new JSONObject().put("Type","order_alert").put("Data",new JSONObject()
            .put("ClientId","1000000001").put("OrderNo","12345").put("CorrelationId","sat-test")
            .put("Status",status).put("Quantity",10).put("TradedQty",filled).put("RemainingQuantity",remaining)
            .put("AvgTradedPrice",filled>0?100.5:0).put("LastUpdatedTime",time).put("Exchange","NSE")
            .put("Segment","E").put("TxnType","S").put("SecurityId","11536")).toString();
    }
    void send(String message){listeners.getLast().onText(sockets.getLast(),message,true);}
    @Test void individualAuthenticationUsesDocumentedMessageAndOneSocketPerCredential() {
        service.refresh();service.refresh();verify(connector,times(1)).connect(any());
        var body=org.mockito.ArgumentCaptor.forClass(CharSequence.class);verify(sockets.getFirst()).sendText(body.capture(),eq(true));
        JSONObject auth=new JSONObject(body.getValue().toString());
        assertThat(auth.getString("UserType")).isEqualTo("SELF");assertThat(auth.getJSONObject("LoginReq").getInt("MsgCode")).isEqualTo(42);
        assertThat(auth.getJSONObject("LoginReq").getString("ClientId")).isEqualTo("1000000001");
        assertThat(auth.getJSONObject("LoginReq").getString("Token")).isEqualTo("test-token");
        verify(events,timeout(1000)).publishEvent(isA(DhanOrderStreamConnectedEvent.class));
    }
    @Test void assemblesFragmentsAndNormalizesAccountScopedOrderMessages() {
        service.refresh();String message=alert("Traded",10,0,"2026-10-19 10:00:00");int mid=message.length()/2;
        listeners.getFirst().onText(sockets.getFirst(),message.substring(0,mid),false);
        verify(events,never()).publishEvent(isA(DhanOrderUpdateEvent.class));
        listeners.getFirst().onText(sockets.getFirst(),message.substring(mid),true);
        var event=org.mockito.ArgumentCaptor.forClass(DhanOrderUpdateEvent.class);verify(events,timeout(1000)).publishEvent(event.capture());
        assertThat(event.getValue().credentialId()).isEqualTo(7L);
        var row=new JSONObject(event.getValue().payload());assertThat(row.getString("orderStatus")).isEqualTo("TRADED");
        assertThat(row.getString("transactionType")).isEqualTo("SELL");assertThat(row.getDouble("averageTradedPrice")).isEqualTo(100.5);
        assertThat(event.getValue().toString()).doesNotContain("test-token","1000000001");
    }
    @Test void ignoresForeignMalformedDuplicateAndRegressiveUpdates() {
        service.refresh();
        send(alert("Pending",0,10,"2026-10-19 09:59:59").replace("1000000001","9999999999"));send("not-json");
        send(alert("Traded",10,0,"2026-10-19 10:00:00"));
        verify(events,timeout(1000)).publishEvent(isA(DhanOrderUpdateEvent.class));
        send(alert("Traded",10,0,"2026-10-19 10:00:00"));
        send(alert("Pending",0,10,"2026-10-19 09:59:58"));send(alert("Pending",0,10,"2026-10-19 10:00:01"));
        send(alert("PART_TRADED",3,8,"2026-10-19 10:00:02"));
        verify(events,times(1)).publishEvent(isA(DhanOrderUpdateEvent.class));
    }
    @Test void reconnectBackoffRejectsLateOldSocketFramesAndRotatesExpiredSessions() {
        service.refresh();var old=listeners.getFirst();old.onClose(sockets.getFirst(),1006,"ignored-sensitive-close-text");
        service.refresh();verify(connector,times(1)).connect(any());
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(5),ZoneOffset.UTC));service.refresh();verify(connector,times(2)).connect(any());
        old.onText(sockets.getFirst(),alert("TRADED",10,0,"2026-10-19 10:00:00"),true);
        verify(events,never()).publishEvent(isA(DhanOrderUpdateEvent.class));
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("rotated-token").expiry(now.plusSeconds(7200)).build()));
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC));service.refresh();verify(connector,times(3)).connect(any());verify(sockets.get(1),atLeastOnce()).abort();
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.empty());ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC));service.refresh();verify(sockets.get(2),atLeastOnce()).abort();
    }
    @Test void pendingExitKeepsStreamWhenNewTradingDisabledAndHeartbeatDetectsDeadConnection() {
        var broker=brokers.findAllByBrokerName("Dhan").getFirst();broker.setTradingEnabled(false);
        service.refresh();verifyNoInteractions(connector);
        when(trades.findByStatusIn(any())).thenReturn(List.of(TriggeredTradeSetupEntity.builder().brokerCredentialsId(7L).build()));
        ReflectionTestUtils.setField(service,"configurationDirty",true);service.refresh();verify(connector,times(1)).connect(any());
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(35),ZoneOffset.UTC));service.refresh();verify(sockets.getFirst()).sendPing(any());
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(100),ZoneOffset.UTC));service.refresh();verify(sockets.getFirst(),atLeastOnce()).abort();
    }
    @Test void heartbeatDoesNotScanDatabaseAndQueueGapReconnectsForCatchup() {
        service.refresh();clearInvocations(brokers,trades,tokens,crypto);
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(35),ZoneOffset.UTC));service.refresh();
        verifyNoInteractions(brokers,trades,tokens,crypto);verify(sockets.getFirst()).sendPing(any());
        ReflectionTestUtils.setField(service,"deliveryGap",true);service.refresh();verify(sockets.getFirst(),atLeastOnce()).abort();
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(40),ZoneOffset.UTC));service.refresh();
        verify(connector,times(2)).connect(any());verifyNoInteractions(brokers,trades,tokens,crypto);
    }

}
