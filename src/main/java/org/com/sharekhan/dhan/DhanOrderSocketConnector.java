package org.com.sharekhan.dhan;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class DhanOrderSocketConnector {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public CompletableFuture<WebSocket> connect(WebSocket.Listener listener) {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://api-order-update.dhan.co"),listener);
    }
}
