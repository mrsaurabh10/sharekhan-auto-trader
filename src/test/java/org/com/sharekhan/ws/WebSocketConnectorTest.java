package org.com.sharekhan.ws;

import jakarta.websocket.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketConnectorTest {
    @Test void sendsAreConfirmedOnlyWhenAnOpenSessionActuallyAcceptsTheFrame() throws Exception {
        var connector=new WebSocketConnector();assertFalse(connector.trySend("feed"));
        var session=mock(Session.class);var remote=mock(RemoteEndpoint.Basic.class);
        when(session.isOpen()).thenReturn(true);when(session.getBasicRemote()).thenReturn(remote);
        connector.setSession(session);assertTrue(connector.trySend("feed"));verify(remote).sendText("feed");
        doThrow(new java.io.IOException("test")).when(remote).sendText("failed");
        assertFalse(connector.trySend("failed"));
        var replacement=mock(Session.class);when(replacement.isOpen()).thenReturn(true);
        connector.setSession(replacement);assertFalse(connector.clearSession(session));assertTrue(connector.isConnected());
        assertTrue(connector.clearSession(replacement));assertFalse(connector.isConnected());
    }
}
