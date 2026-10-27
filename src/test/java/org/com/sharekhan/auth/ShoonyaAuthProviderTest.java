package org.com.sharekhan.auth;

import org.com.sharekhan.ShoonyaTokenFetcher;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.service.ShoonyaOAuthService;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShoonyaAuthProviderTest {
    private final ShoonyaProperties properties = properties();
    private final ShoonyaTokenFetcher fetcher = mock(ShoonyaTokenFetcher.class);
    private final ShoonyaOAuthService oauth = mock(ShoonyaOAuthService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ShoonyaAuthProvider provider = new ShoonyaAuthProvider(properties, fetcher, oauth, crypto);

    @Test void globalPasswordIsDecryptedOnlyForTheLoginCall() {
        properties.setPassword("ENC(test-ciphertext)");
        when(crypto.decrypt("test-ciphertext")).thenReturn("test-new-password");
        when(fetcher.fetchAuthorizationCode(any(), anyString())).thenReturn("test-code");
        provider.loginAndFetchToken();
        var resolved = ArgumentCaptor.forClass(ShoonyaProperties.class);
        verify(fetcher).fetchAuthorizationCode(resolved.capture(), eq(properties.getOauthUrl()+"?client_id=test-client"));
        assertThat(resolved.getValue().getPassword()).isEqualTo("test-new-password");
        assertThat(resolved.getValue().isBrowserHeadless()).isFalse();
        assertThat(resolved.getValue().getAccessTokenTtlSeconds()).isEqualTo(1234);
        assertThat(properties.getPassword()).isEqualTo("ENC(test-ciphertext)");
        verify(oauth).exchangeAuthorizationCode(resolved.getValue(), "test-code");
    }

    @Test void legacyUnwrappedPasswordRemainsCompatible() {
        properties.setPassword("test-legacy-password");
        provider.loginAndFetchToken();
        var resolved = ArgumentCaptor.forClass(ShoonyaProperties.class);
        verify(fetcher).fetchAuthorizationCode(resolved.capture(), anyString());
        assertThat(resolved.getValue().getPassword()).isEqualTo("test-legacy-password");
        verifyNoInteractions(crypto);
    }

    @Test void unreadableEncryptedPasswordFailsClosedWithoutStartingBrowser() {
        properties.setPassword("ENC(test-bad-ciphertext)");
        when(crypto.decrypt(anyString())).thenThrow(new RuntimeException("test-sensitive-detail"));
        assertThatThrownBy(provider::loginAndFetchToken).isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to decrypt configured Shoonya password").hasNoCause();
        verifyNoInteractions(fetcher, oauth);
    }

    @Test void truncatedEncryptedPasswordFailsClosedWithoutStartingBrowser() {
        properties.setPassword("ENC(test-truncated");
        assertThatThrownBy(provider::loginAndFetchToken).hasMessage("Unable to decrypt configured Shoonya password");
        verifyNoInteractions(crypto, fetcher, oauth);
    }

    @Test void accountPasswordOverridesGlobalPasswordWithoutDecryptingIt() {
        properties.setPassword("ENC(test-invalid-global");
        when(crypto.decrypt("test-account-ciphertext")).thenReturn("test-account-password");
        var account = BrokerCredentialsEntity.builder().brokerPassword("test-account-ciphertext").build();
        provider.loginAndFetchToken(account);
        var resolved = ArgumentCaptor.forClass(ShoonyaProperties.class);
        verify(fetcher).fetchAuthorizationCode(resolved.capture(), anyString());
        assertThat(resolved.getValue().getPassword()).isEqualTo("test-account-password");
        verify(crypto).decrypt("test-account-ciphertext");
        verifyNoMoreInteractions(crypto);
    }

    private static ShoonyaProperties properties() {
        var p = new ShoonyaProperties();p.setClientId("test-client");p.setUid("test-user");
        p.setSecretCode("test-secret");p.setBrowserHeadless(false);p.setAccessTokenTtlSeconds(1234);return p;
    }
}
