package org.com.sharekhan.ws;

import jakarta.websocket.*;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketOwnedFeedRestoreTest {
    final WebSocketConnector connector=new WebSocketConnector();
    final WebSocketSubscriptionHelper feeds=new WebSocketSubscriptionHelper(connector);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final QuoteCacheService quotes=new QuoteCacheService();
    final WebSocketClientService client=new WebSocketClientService(mock(TokenStoreService.class),mock(CryptoService.class),mock(PriceTriggerService.class),
            execution,connector,mock(LtpWebSocketHandler.class),feeds,quotes,new LtpCacheService(),mock(ApplicationEventPublisher.class));
    WebSocketOwnedFeedRestoreTest(){
        ReflectionTestUtils.setField(client,"scripExecutorManager",mock(ScripExecutorManager.class));
        ReflectionTestUtils.setField(client,"scriptMasterRepository",mock(ScriptMasterRepository.class));
    }
    Session session(RemoteEndpoint.Basic remote){var session=mock(Session.class);when(session.isOpen()).thenReturn(true);when(session.getBasicRemote()).thenReturn(remote);return session;}
    @Test void reconnectRestoresStrategyOnlyDemandBeforeTradesAndConfirmsOnlyAnActualTick() throws Exception {
        feeds.retainLtpFeed("NIFTY_3C_SUB:188","NC20000");var remote=mock(RemoteEndpoint.Basic.class);var first=session(remote);
        client.onOpen(first);
        var order=inOrder(remote,execution);
        order.verify(remote).sendText("{\"action\":\"subscribe\",\"key\":[\"feed\",\"ack\"],\"value\":[\"\"]}");
        order.verify(remote).sendText("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"NC20000\"]}");
        order.verify(execution).subscribeForOpenTrades();
        quotes.recordQuote(20000,null,null,25000d,"SHOONYA","NC","26000:Nifty 50",null,null);
        assertEquals("WAITING_FOR_TICKS",feeds.ltpFeedStatus("NC20000").get("state"));
        client.onMessage("{\"message\":\"feed\",\"data\":{\"scripCode\":20000,\"exchange\":\"NC\",\"ltp\":25001}}");
        assertEquals("RECEIVING",feeds.ltpFeedStatus("NC20000").get("state"));
        var replacement=session(remote);client.onOpen(replacement);
        assertEquals("WAITING_FOR_TICKS",feeds.ltpFeedStatus("NC20000").get("state"));
        client.onClose(first,new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE,"old session"));
        assertTrue(client.isConnected());
        verify(remote,times(2)).sendText("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"NC20000\"]}");
    }
    @Test void unsentInitialSubscriptionClosesTheSocketAndKeepsDesiredStrategyDemand() throws Exception {
        feeds.retainLtpFeed("NIFTY_3C_SUB:188","NC20000");var remote=mock(RemoteEndpoint.Basic.class);var session=session(remote);
        doThrow(new java.io.IOException("test")).when(remote).sendText(anyString());client.onOpen(session);
        verify(session).close();assertFalse(client.isConnected());verifyNoInteractions(execution);
        assertTrue(feeds.getActiveScripKeys().contains("NC20000"));assertEquals("DISCONNECTED",feeds.ltpFeedStatus("NC20000").get("state"));
    }
}
