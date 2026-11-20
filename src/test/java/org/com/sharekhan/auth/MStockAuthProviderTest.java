package org.com.sharekhan.auth;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MStockAuthProviderTest {
    @Test void verifiesTotpForTheSelectedAccountAndExpiresByIndianMidnight() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var form=new AtomicReference<String>();var response=new AtomicReference<String>("{\"status\":\"success\",\"data\":{\"access_token\":\"session-token\"}}");
        server.createContext("/session/verifytotp",exchange->{
            form.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            var crypto=mock(CryptoService.class);when(crypto.decrypt("encrypted-key")).thenReturn("key+&");when(crypto.decrypt("encrypted-secret")).thenReturn("JBSWY3DPEHPK3PXP");
            var provider=new MStockAuthProvider(new MStockProperties(),crypto);
            ReflectionTestUtils.setField(provider,"verifyTotpUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/session/verifytotp");
            var account=BrokerCredentialsEntity.builder().apiKey("encrypted-key").totpSecret("encrypted-secret").build();
            var result=provider.loginAndFetchToken(account);
            assertThat(result.token()).isEqualTo("session-token");
            assertThat(form.get()).contains("api_key=key%2B%26").containsPattern("totp=[0-9]{6}");
            var now=ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            assertThat(result.expiresIn()).isBetween(1L,Duration.between(now,now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).getSeconds()+2);
            response.set("{\"status\":\"error\",\"message\":\"secret-response\"}");
            assertThatThrownBy(()->provider.loginAndFetchToken(account)).hasMessageNotContaining("secret-response");
        } finally {server.stop(0);}
    }
}
