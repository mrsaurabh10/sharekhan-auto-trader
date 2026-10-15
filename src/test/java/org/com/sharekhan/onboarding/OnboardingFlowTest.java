package org.com.sharekhan.onboarding;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(properties={"app.onboarding.enabled=true","spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("onboarding-test")
@Import({AccountService.class, AdminUserDetailsService.class,OnboardingFlowTest.Beans.class})
class OnboardingFlowTest {
    @TestConfiguration static class Beans {
        @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(4);}
        @Bean SessionRegistry sessions(){return new SessionRegistryImpl();}
    }
    @Autowired AccountService accounts;
    @Autowired AdminUserDetailsService login;
    @Autowired AppUserRepository users;
    @Autowired AccountProfileRepository profiles;
    @Autowired PasswordEncoder passwords;
    @MockBean AccountMailService mail;
    @MockBean CryptoService crypto;
    @Test void registerVerifyLoginAndRecoverUsingRealDatabase() {
        when(mail.configured()).thenReturn(true);when(crypto.hasPersistentKey()).thenReturn(true);
        accounts.register(new AccountService.Registration("Alex","alex@example.test","first long password"));
        assertThat(login.loadUserByUsername("alex@example.test").isEnabled()).isFalse();
        var verification=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alex@example.test"),verification.capture(),eq("VERIFY"));
        accounts.redeem(new AccountService.TokenRequest(verification.getValue(),null),false);
        assertThat(login.loadUserByUsername("alex@example.test").isEnabled()).isTrue();
        var profile=profiles.findByEmail("alex@example.test").orElseThrow();profile.setTokenExpiresAt(null);profiles.saveAndFlush(profile);
        accounts.requestEmail("alex@example.test",true);
        var recovery=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alex@example.test"),recovery.capture(),eq("RESET"));
        accounts.redeem(new AccountService.TokenRequest(recovery.getValue(),"second long password"),true);
        assertThat(passwords.matches("second long password",users.findByUsername("alex@example.test").orElseThrow().getPassword())).isTrue();
        assertThatThrownBy(()->accounts.redeem(new AccountService.TokenRequest(recovery.getValue(),"third long password"),true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
