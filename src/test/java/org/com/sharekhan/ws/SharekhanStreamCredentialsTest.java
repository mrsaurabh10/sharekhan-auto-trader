package org.com.sharekhan.ws;

import org.junit.jupiter.api.Test;
import org.com.sharekhan.util.CryptoService;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SharekhanStreamCredentialsTest {
    CryptoService crypto(){var crypto=new CryptoService();ReflectionTestUtils.setField(crypto,"base64Key",Base64.getEncoder().encodeToString(new byte[32]));crypto.init();return crypto;}
    Map<String,String> query(URI uri){Map<String,String> result=new HashMap<>();for(var pair:uri.getRawQuery().split("&")){var parts=pair.split("=",2);result.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}return result;}
    @Test void decryptsStoredApiKeyAndEncodesBothCredentials(){
        var crypto=crypto();var uri=WebSocketClientService.connectionUri("test-token+with&reserved=characters",""+crypto.encrypt("test-api+key&value"),crypto);
        assertThat(uri.getHost()).isEqualTo("stream.sharekhan.com");
        assertThat(query(uri)).containsExactlyInAnyOrderEntriesOf(Map.of("ACCESS_TOKEN","test-token+with&reserved=characters","API_KEY","test-api+key&value"));
        assertThat(uri.getRawQuery()).doesNotContain("test-api+key&value");
    }
    @Test void keepsLegacyPlaintextKeysWorking(){assertThat(query(WebSocketClientService.connectionUri("test-token","legacy-test-key",crypto())).get("API_KEY")).isEqualTo("legacy-test-key");}
    @Test void missingCredentialsCannotConnect(){assertThatThrownBy(()->WebSocketClientService.connectionUri("","",crypto())).isInstanceOf(IllegalArgumentException.class);}
}
