package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MStockLtpReadOnlyTest {
    @Test
    void unauthorizedOptionReadIsBoundedAndNeverRefreshesAuthentication() throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(401);
        when(connection.getErrorStream()).thenReturn(new ByteArrayInputStream(
                "{\"status\":\"error\",\"error_type\":\"TokenException\"}".getBytes(StandardCharsets.UTF_8)));
        var tokens = mock(TokenStoreService.class);
        when(tokens.getAccessToken(Broker.MSTOCK)).thenReturn("test-session");
        var service = new MStockLtpService(tokens, mock(CryptoService.class)) {
            @Override HttpURLConnection openConnection(URL url) { return connection; }
        };
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        assertThatThrownBy(() -> service.fetchLtpForInstrumentReadOnly("NFO:BANKINDIA26NOV130CE"))
                .isInstanceOf(RuntimeException.class);
        verify(tokens, never()).refreshToken(any(), any());
        verify(connection).setConnectTimeout(500);
        verify(connection).setReadTimeout(750);
        verify(connection).disconnect();
    }
}
