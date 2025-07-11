package org.com.sharekhan.login;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SharekhanLoginAutomationTest {

    private SharekhanConnect mockConnect;

    @BeforeEach
    void setUp() {
        mockConnect = mock(SharekhanConnect.class);
    }

    @Test
    void testExtractTokensFromUrl() {
        String url = "https://test.com?request_token=abc123%2Bxyz&state=1234";
        var tokens = SharekhanLoginAutomation.extractTokensFromUrl(url);
        assertEquals("abc123%2Bxyz", tokens.get("request_token"));
        assertEquals("1234", tokens.get("state"));
    }

    @Test
    void testGenerateSessionReturnsAccessToken() throws IOException, SharekhanAPIException {
        JSONObject mockResponse = new JSONObject("""
            {
                "data": {
                    "token": "mock-access-token"
                }
            }
        """);

        when(mockConnect.generateSession(anyString(), anyString(), any(), anyLong(), anyString(), anyLong()))
                .thenReturn(mockResponse);

        String token = SharekhanLoginAutomation.generateSession(mockConnect, "mockRequestToken");
        assertEquals("mock-access-token", token);
    }

    // Note: fetchAccessToken() uses real browser automation and TOTP — integration testing only.
}
