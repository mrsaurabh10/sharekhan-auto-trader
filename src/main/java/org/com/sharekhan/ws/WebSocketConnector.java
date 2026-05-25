package org.com.sharekhan.ws;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WebSocketConnector {

    private final Object sendLock = new Object();
    private volatile Session session;

    public void setSession(Session session) {
        this.session = session;
    }

    public void send(String message) {
        Session currentSession = this.session;
        if (currentSession != null && currentSession.isOpen()) {
            try {
                synchronized (sendLock) {
                    if (currentSession.isOpen()) {
                        currentSession.getBasicRemote().sendText(message);
                    }
                }
            } catch (Exception e) {
                log.error("❌ Failed to send WebSocket message: {}", e.getMessage(), e);
            }
        } else {
            log.warn("⚠️ WebSocket session is not open. Skipping message.");
        }
    }
}
