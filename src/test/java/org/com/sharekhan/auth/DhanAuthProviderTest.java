package org.com.sharekhan.auth;

import java.net.URI;
import java.time.*;
import java.util.List;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DhanAuthProviderTest {
    CryptoService crypto = mock(CryptoService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);
    BrokerCredentialsEntity creds() {
        var b = BrokerCredentialsEntity.builder().brokerName("Dhan").clientCode("encrypted-client")
            .apiKey("encrypted-key").secretKey("encrypted-secret").brokerPassword("encrypted-pin").totpSecret("encrypted-seed").build();
        when(crypto.decrypt("encrypted-client")).thenReturn("1000000001");
        when(crypto.decrypt("encrypted-key")).thenReturn("test-app-key");
        when(crypto.decrypt("encrypted-secret")).thenReturn("test-app-secret");
        when(crypto.decrypt("encrypted-pin")).thenReturn("012345");
        when(crypto.decrypt("encrypted-seed")).thenReturn("JBSWY3DPEHPK3PXP");
        return b;
    }
    JSONObject valid() { return new JSONObject().put("dhanClientId","1000000001").put("accessToken","test-token")
        .put("expiryTime","2026-10-17T12:30:00.000"); }
    @Test void totpUsesDocumentedPostAndInterpretsLocalExpiryAsIst() {
        var b = creds();
        var provider = new DhanAuthProvider(crypto, (uri, method, headers) -> {
            assertThat(method).isEqualTo(HttpMethod.POST);
            assertThat(uri.getHost()).isEqualTo("auth.dhan.co");
            assertThat(uri.getPath()).isEqualTo("/app/generateAccessToken");
            assertThat(uri.getQuery()).matches("dhanClientId=1000000001&pin=012345&totp=[0-9]{6}");
            return valid();
        }, clock);
        assertThat(provider.loginAndFetchToken(b)).isEqualTo(new AuthTokenResult("test-token",3600));
    }
    @Test void apiKeyFlowUsesHeadersAndEncodesTokenId() {
        var b = creds();
        var provider = new DhanAuthProvider(crypto, (uri, method, headers) -> {
            assertThat(headers.getFirst("app_id")).isEqualTo("test-app-key");
            assertThat(headers.getFirst("app_secret")).isEqualTo("test-app-secret");
            assertThat(uri.toString()).doesNotContain("test-app");
            if (uri.getPath().endsWith("generate-consent")) {
                assertThat(method).isEqualTo(HttpMethod.POST);
                assertThat(uri.getQuery()).isEqualTo("client_id=1000000001");
                return new JSONObject().put("status","success").put("consentAppId","test-consent");
            }
            assertThat(method).isEqualTo(HttpMethod.GET);
            assertThat(uri.getRawQuery()).isEqualTo("tokenId=test%26other%3Dvalue");
            return valid().put("expiryTime","2026-10-17T07:00:00Z");
        }, clock);
        assertThat(provider.beginConsent(b)).isEqualTo("https://auth.dhan.co/login/consentApp-login?consentAppId=test-consent");
        assertThat(provider.consumeConsent(b,"test&other=value").expiresIn()).isEqualTo(3600);
    }
    @Test void rejectsWrongAccountExpiredMalformedAndEmptyTokensWithoutLeakingResponse() {
        var b = creds();
        for (var body : List.of(valid().put("dhanClientId","other-client"),valid().put("accessToken",""),
                valid().put("expiryTime","2026-10-17T11:30:30"),valid().put("expiryTime","invalid"),
                valid().put("status","failure"),new JSONObject().put("secret","sensitive-response"))) {
            var provider = new DhanAuthProvider(crypto,(uri,method,headers)->body,clock);
            assertThatThrownBy(()->provider.loginAndFetchToken(b)).hasMessageStartingWith("Dhan authentication failed")
                .hasMessageNotContaining("sensitive-response").hasNoCause();
        }
    }
    @Test void transportAndDecryptionErrorsAreSanitizedAndNeverFallBackToPlaintext() {
        var b = creds();
        var provider = new DhanAuthProvider(crypto,(uri,method,headers)-> { throw new RuntimeException("private-url-and-pin"); },clock);
        assertThatThrownBy(()->provider.loginAndFetchToken(b)).hasMessageNotContaining("private-url-and-pin").hasNoCause();
        when(crypto.decrypt("encrypted-pin")).thenThrow(new IllegalStateException("sensitive-key"));
        assertThatThrownBy(()->provider.loginAndFetchToken(b)).hasMessageNotContaining("sensitive-key").hasNoCause();
        assertThatThrownBy(provider::loginAndFetchToken).hasMessageContaining("user-scoped");
    }
}
