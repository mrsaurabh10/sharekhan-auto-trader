package org.com.sharekhan.service.broker;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class MStockTransportTest {
    @Test void sendsDocumentedHeadersMethodsAndUrlEncodedFormsWithoutRedirecting() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var method=new AtomicReference<String>();var body=new AtomicReference<String>();var auth=new AtomicReference<String>();var version=new AtomicReference<String>();
        server.createContext("/orders/regular",exchange->{
            method.set(exchange.getRequestMethod());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));version.set(exchange.getRequestHeaders().getFirst("X-Mirae-Version"));
            byte[] response="{\"status\":\"success\",\"data\":{\"order_id\":\"123\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
        });server.start();
        try {
            var service=new MStockBrokerService(null,null,null,null,null,null);
            ReflectionTestUtils.setField(service,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort());
            var session=new MStockBrokerService.Session("test-token","test-key",true);
            for(String verb:new String[]{"POST","PUT","DELETE","GET"}) {
                var result=service.exchange(verb,"/orders/regular",verb.equals("POST")||verb.equals("PUT")?Map.of("tradingsymbol","A&B + EQ"):Map.of(),session);
                assertThat(result.ok()).isTrue();assertThat(method.get()).isEqualTo(verb);
                assertThat(auth.get()).isEqualTo("token test-key:test-token");assertThat(version.get()).isEqualTo("1");
                if(verb.equals("POST")||verb.equals("PUT"))assertThat(URLDecoder.decode(body.get(),StandardCharsets.UTF_8)).isEqualTo("tradingsymbol=A&B + EQ");
                else assertThat(body.get()).isEmpty();
            }
        } finally {server.stop(0);}
    }
}
