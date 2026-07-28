package org.com.sharekhan.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.enums.Broker;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ShoonyaQuoteServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

    @Test
    void postsUidExchangeAndTokenToGetQuotesWithSessionJKey() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/NorenWClientAPI/GetQuotes", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"stat\":\"Ok\",\"bp1\":\"101.25\",\"sp1\":\"101.40\"}");
        });
        server.start();

        ShoonyaProperties properties = new ShoonyaProperties();
        properties.setApiUrl("http://localhost:" + server.getAddress().getPort() + "/NorenWClientAPI");
        properties.setUserId("FA12345");
        TokenStoreService tokens = mock(TokenStoreService.class);
        when(tokens.getAccessToken(Broker.SHOONYA)).thenReturn("session-token");

        JSONObject result = new ShoonyaQuoteService(properties, tokens, mock(BrokerAuthProviderRegistry.class),
                mock(ShoonyaInstrumentMasterService.class)).getQuotes("NFO", "12345", null);

        assertThat(result.getString("bp1")).isEqualTo("101.25");
        String[] parts = body.get().split("&", 2);
        JSONObject jData = new JSONObject(URLDecoder.decode(parts[0].substring("jData=".length()), StandardCharsets.UTF_8));
        assertThat(jData.toMap()).containsEntry("uid", "FA12345").containsEntry("exch", "NFO").containsEntry("token", "12345");
        assertThat(URLDecoder.decode(parts[1], StandardCharsets.UTF_8)).isEqualTo("jKey=session-token");
        verify(tokens, never()).updateToken(any(), anyString(), anyLong());
    }

    private static void reply(HttpExchange exchange, int status, String response) throws java.io.IOException {
        exchange.sendResponseHeaders(status, response.length());
        exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
