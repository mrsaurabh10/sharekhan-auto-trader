package org.com.sharekhan.onboarding;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.AdminUserRepository;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccountServiceTest {
    AppUserRepository users = mock(AppUserRepository.class);
    AdminUserRepository admins = mock(AdminUserRepository.class);
    AccountProfileRepository profiles = mock(AccountProfileRepository.class);
    AccountMailService mail = mock(AccountMailService.class);
    CryptoService crypto = mock(CryptoService.class);
    BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    SessionRegistryImpl sessions = new SessionRegistryImpl();
    AccountService service = new AccountService(users, admins, profiles, passwords, mail, crypto, sessions);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(mail.configured()).thenReturn(true); when(crypto.hasPersistentKey()).thenReturn(true);
        when(users.saveAndFlush(any())).thenAnswer(i -> { AppUser u=i.getArgument(0); u.setId(7L); return u; });
    }
    @Test void registrationHashesPasswordAndEmailsOnlyUnpersistedToken() {
        service.register(new AccountService.Registration("Alex", " Alex@Example.test ", "a long unique password"));
        var u=org.mockito.ArgumentCaptor.forClass(AppUser.class); verify(users).saveAndFlush(u.capture());
        assertThat(u.getValue().getUsername()).isEqualTo("alex@example.test");
        assertThat(passwords.matches("a long unique password", u.getValue().getPassword())).isTrue();
        var p=org.mockito.ArgumentCaptor.forClass(AccountProfile.class); verify(profiles).saveAndFlush(p.capture());
        var token=org.mockito.ArgumentCaptor.forClass(String.class); verify(mail).send(eq("alex@example.test"),token.capture(),eq("VERIFY"));
        assertThat(token.getValue()).hasSize(43);
        assertThat(p.getValue().getTokenHash()).isEqualTo(AccountService.hash(token.getValue())).doesNotContain(token.getValue());
        assertThat(p.getValue().getVerifiedAt()).isNull();
    }
    @Test void registrationFailsClosedWithoutMailOrPersistentEncryption() {
        when(crypto.hasPersistentKey()).thenReturn(false);
        assertThatThrownBy(()->service.register(new AccountService.Registration("Alex","alex@example.test","long password here"))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users);
    }
    @Test void existingAdminNameCannotBeRegisteredAsAppUser() {
        when(admins.existsByUsernameIgnoreCase("admin@example.test")).thenReturn(true);
        service.register(new AccountService.Registration("Alex","admin@example.test","long password here"));
        verify(users,never()).saveAndFlush(any()); verify(mail,never()).send(any(),any(),any());
    }
    @Test void verifiesOnceAndRejectsReplay() {
        var profile=tokenProfile("VERIFY",Instant.now().plusSeconds(60));
        service.redeem(new AccountService.TokenRequest("x".repeat(43),null),false);
        assertThat(profile.getVerifiedAt()).isNotNull(); assertThat(profile.getTokenHash()).isNull();
        assertThatThrownBy(()->service.redeem(new AccountService.TokenRequest("x".repeat(43),null),false)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsExpiredAndWrongPurposeTokens() {
        var p=tokenProfile("RESET",Instant.now().minusSeconds(1));
        assertThatThrownBy(()->service.redeem(new AccountService.TokenRequest("x".repeat(43),null),true)).isInstanceOf(ResponseStatusException.class);
        p.setTokenExpiresAt(Instant.now().plusSeconds(60));
        assertThatThrownBy(()->service.redeem(new AccountService.TokenRequest("x".repeat(43),null),false)).isInstanceOf(ResponseStatusException.class);
        verify(users,never()).save(any());
    }
    @Test void resetChangesPasswordAndExpiresExistingSessions() {
        tokenProfile("RESET",Instant.now().plusSeconds(60));
        var user=new AppUser(7L,"alex@example.test",passwords.encode("old password here"),null,null);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        var principal=org.springframework.security.core.userdetails.User.withUsername(user.getUsername()).password(user.getPassword()).roles("USER").build();
        sessions.registerNewSession("old-session",principal);
        service.redeem(new AccountService.TokenRequest("x".repeat(43),"new password here"),true);
        assertThat(passwords.matches("new password here",user.getPassword())).isTrue();
        assertThat(sessions.getSessionInformation("old-session").isExpired()).isTrue();
    }
    @Test void unknownRecoveryAddressDoesNotSendMail() {
        service.requestEmail("unknown@example.test",true);
        verify(mail,never()).send(any(),any(),any());
    }
    @Test void rejectsInvalidEmailAndBcryptTruncation() {
        for(String value:new String[]{"missing-at","a b@example.test","a@example.test\r\nInjected: x"})
            assertThatThrownBy(()->AccountService.normalizeEmail(value)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->AccountService.validatePassword("a".repeat(73))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->AccountService.validatePassword("€".repeat(25))).isInstanceOf(ResponseStatusException.class);
    }
    AccountProfile tokenProfile(String purpose,Instant expiry) {
        AccountProfile p=new AccountProfile();p.setUserId(7L);p.setEmail("alex@example.test");
        p.setTokenHash(AccountService.hash("x".repeat(43)));p.setTokenPurpose(purpose);p.setTokenExpiresAt(expiry);
        when(profiles.findByTokenHash(AccountService.hash("x".repeat(43)))).thenReturn(Optional.of(p));return p;
    }
}
