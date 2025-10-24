package org.com.sharekhan.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LtpWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("✅ WebSocket connected: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("❌ WebSocket disconnected: " + session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("⚠️ WebSocket error: " + exception.getMessage());
        sessions.remove(session);
    }

    /** Broadcast updated LTP to all clients */
    public void broadcastLtp(int scripCode, double ltp) {
        String msg = String.format("{\"scripCode\":%d, \"ltp\":%.2f}", scripCode, ltp);

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(msg));
                } catch (IOException e) {
                    System.err.println("⚠️ Failed to send message to session " + session.getId());
                    e.printStackTrace();
                }
            }
        }
    }

    public int getActiveConnections() {
        return sessions.size();
    }
}
