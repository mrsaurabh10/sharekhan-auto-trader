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
        synchronized(sendLock){this.session=session;}
    }

    public boolean clearSession(Session expected) {
        synchronized(sendLock){
            if(this.session!=expected)return false;
            this.session=null;return true;
        }
    }

    public boolean isConnected(){Session current=session;return current!=null&&current.isOpen();}

    public void send(String message) {
        if(!trySend(message))log.warn("WebSocket message was not sent; connection unavailable or send failed");
    }

    /** Success means the frame was sent, not that a feed was acknowledged or is receiving ticks. */
    public boolean trySend(String message) {
        synchronized(sendLock){
            Session current=session;
            if(current==null||!current.isOpen())return false;
            try {current.getBasicRemote().sendText(message);return true;}
            catch(Exception failure){log.warn("WebSocket send failed: {}",failure.getClass().getSimpleName());return false;}
        }
    }
}
