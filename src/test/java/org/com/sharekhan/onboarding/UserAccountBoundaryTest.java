package org.com.sharekhan.onboarding;
import org.com.sharekhan.auth.SharekhanAuthProvider;
import org.com.sharekhan.SharekhanTokenFetcher;
import org.com.sharekhan.config.SharekhanProperties;
import org.com.sharekhan.controller.UserController;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Optional;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class UserAccountBoundaryTest {
    @Test void brokerDetailDoesNotReturnSavedSecrets() {
        var current=mock(CurrentUserService.class);when(current.currentAppUser()).thenReturn(Optional.of(new AppUser(7L,"alex","hash",null,null)));
        var brokers=mock(BrokerCredentialsRepository.class);
        var broker=BrokerCredentialsEntity.builder().id(1L).appUserId(7L).brokerName("Sharekhan").apiKey("encrypted-api-key").brokerPassword("encrypted-password").secretKey("encrypted-secret").totpSecret("encrypted-totp").build();
        when(brokers.findById(1L)).thenReturn(Optional.of(broker));
        var controller=new UserController(current,brokers,mock(CryptoService.class),new BCryptPasswordEncoder(4),mock(UserConfigService.class),mock(UserTradingAccess.class),mock(BrokerConnectionRepository.class),mock(BrokerConnectionService.class),mock(AccountProfileRepository.class),mock(AccountService.class));
        var body=(Map<?,?>)controller.getMyBroker(1L).getBody();
        for (String field : new String[]{"apiKey","brokerPassword","totpSecret","secretKey"}) assertThat(body.containsKey(field)).isFalse();
        assertThat(body.toString()).doesNotContain("encrypted-api-key","encrypted-password","encrypted-secret","encrypted-totp");
        assertThat(body.get("hasApiKey")).isEqualTo(true);
    }
    @Test void unverifiedNewUserCannotLogInAndLegacyUserRemainsCompatible() {
        var admins=mock(AdminUserRepository.class);var users=mock(AppUserRepository.class);var profiles=mock(AccountProfileRepository.class);
        when(users.findByUsername("alex")).thenReturn(Optional.of(new AppUser(7L,"alex","hash",null,null)));
        var service=new AdminUserDetailsService(admins,users,profiles);
        var profile=new AccountProfile();profile.setUserId(7L);when(profiles.findById(7L)).thenReturn(Optional.of(profile));
        assertThat(service.loadUserByUsername("alex").isEnabled()).isFalse();
        profile.setVerifiedAt(java.time.Instant.now());assertThat(service.loadUserByUsername("alex").isEnabled()).isTrue();
        when(profiles.findById(7L)).thenReturn(Optional.empty());assertThat(service.loadUserByUsername("alex").isEnabled()).isTrue();
    }
    @Test void perUserAuthenticationCannotUseGlobalCredentials() {
        var props=mock(SharekhanProperties.class);var fetcher=mock(SharekhanTokenFetcher.class);
        var provider=new SharekhanAuthProvider(props,fetcher,mock(CryptoService.class));
        assertThatThrownBy(()->provider.loginAndFetchToken(new BrokerCredentialsEntity())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(props,fetcher);
    }
}
