package org.com.sharekhan.mstock;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class MStockOrderSocketConnector {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CompletableFuture<WebSocket> connect(String apiKey, String accessToken, WebSocket.Listener listener) {
        // The broker requires credentials in the URI. Never log this URI or connection exceptions.
        var uri = URI.create("wss://ws.mstock.trade?API_KEY=" + encode(apiKey) + "&ACCESS_TOKEN=" + encode(accessToken));
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener)
                .orTimeout(12, TimeUnit.SECONDS);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
