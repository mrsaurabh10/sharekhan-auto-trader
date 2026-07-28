package org.com.sharekhan.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShoonyaAuthProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

    @Test
    void sendsHashedCredentialsToQuickAuthAndReturnsSessionToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/NorenWClientAPI/QuickAuth", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"stat\":\"Ok\",\"susertoken\":\"session-token\"}");
        });
        server.start();

        ShoonyaProperties properties = properties();
        properties.setApiUrl("http://localhost:" + server.getAddress().getPort() + "/NorenWClientAPI");
        properties.setAuthUrl("http://localhost:" + server.getAddress().getPort() + "/NorenWClientAPI");
        AuthTokenResult result = new ShoonyaAuthProvider(properties, mock(CryptoService.class)).loginAndFetchToken();

        assertThat(result.token()).isEqualTo("session-token");
        JSONObject jData = new JSONObject(URLDecoder.decode(body.get().substring("jData=".length()), StandardCharsets.UTF_8));
        assertThat(jData.getString("uid")).isEqualTo("FA12345");
        assertThat(jData.getString("pwd")).hasSize(64).isNotEqualTo("password");
        assertThat(jData.getString("appkey")).hasSize(64).isNotEqualTo("api-key");
        assertThat(jData.getString("factor2")).hasSize(6);
    }

    private ShoonyaProperties properties() {
        ShoonyaProperties properties = new ShoonyaProperties();
        properties.setUserId("FA12345");
        properties.setPassword("password");
        properties.setVendorCode("FA12345_U");
        properties.setApiKey("api-key");
        properties.setDeviceId("device-id");
        properties.setTotpSecret("JBSWY3DPEHPK3PXP");
        return properties;
    }

    private static void reply(HttpExchange exchange, int status, String response) throws java.io.IOException {
        exchange.sendResponseHeaders(status, response.length());
        exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
