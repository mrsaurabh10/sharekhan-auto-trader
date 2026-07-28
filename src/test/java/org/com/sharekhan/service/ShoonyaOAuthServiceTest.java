package org.com.sharekhan.service;

import com.sun.net.httpserver.HttpServer;
import org.com.sharekhan.config.ShoonyaProperties;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ShoonyaOAuthServiceTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void exchangesAuthorizationCodeUsingDocumentedChecksum() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/NorenWClientAPI/GenAcsTok", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"access_token\":\"oauth-token\",\"USERID\":\"FA12345\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        ShoonyaProperties properties = new ShoonyaProperties();
        properties.setApiUrl("http://localhost:" + server.getAddress().getPort() + "/NorenWClientAPI");
        properties.setClientId("ABC"); properties.setSecretCode("123"); properties.setUid("FA12345");
        var result = new ShoonyaOAuthService(properties).exchangeAuthorizationCode("x1y2z3");

        JSONObject request = new JSONObject(body.get().substring("jData=".length()));
        assertThat(request.getString("checksum")).isEqualTo(sha256("ABC123x1y2z3"));
        assertThat(result.token()).isEqualTo("oauth-token");
        assertThat(result.expiresIn()).isEqualTo(28_800L);
    }

    private static String sha256(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
