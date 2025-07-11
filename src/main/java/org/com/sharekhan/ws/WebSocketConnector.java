package org.com.sharekhan.ws;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class WebSocketConnector {

    private Session session;

    public void setSession(Session session) {
        this.session = session;
    }

    public void send(String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getAsyncRemote().sendText(message);
            } catch (Exception e) {
                log.error("❌ Failed to send WebSocket message: {}", e.getMessage(), e);
            }
        } else {
            log.warn("⚠️ WebSocket session is not open. Skipping message.");
        }
    }
}
