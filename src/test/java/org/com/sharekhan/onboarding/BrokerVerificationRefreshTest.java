package org.com.sharekhan.onboarding;

import java.time.Instant;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop", showSql=false)
@ActiveProfiles("onboarding-test")
@Import(AccessTokenPersistenceService.class)
class BrokerVerificationRefreshTest {
    @org.springframework.boot.test.mock.mockito.MockBean CryptoService crypto;
    @Autowired AccessTokenPersistenceService tokens;
    @Autowired BrokerCredentialsRepository brokers;
    @Autowired BrokerConnectionRepository connections;
    @Autowired AccountProfileRepository profiles;
    @Autowired AccessTokenRepository tokenRows;
    @Autowired TestEntityManager em;
    private final Instant expiry = Instant.parse("2099-01-01T00:00:00Z");

    private BrokerCredentialsEntity broker(boolean active, boolean enabled, Instant verified) {
        var b=brokers.saveAndFlush(BrokerCredentialsEntity.builder().brokerName("Sharekhan")
            .customerId(123L).appUserId(7L).active(active).tradingEnabled(enabled).build());
        if(verified!=null) {
            var c=new BrokerConnection(); c.setCredentialId(b.getId()); c.setVerifiedUntil(verified);
            connections.saveAndFlush(c);
        }
        when(crypto.encrypt(anyString())).thenReturn("encrypted-test-token");
        return b;
    }
    private UserTradingAccess access() {
        return new UserTradingAccess(mock(CurrentUserService.class),brokers,connections,profiles);
    }
    @Test void scheduledCustomerRefreshRepairsExpiredVerificationAndAllowsEntry() {
        var b=broker(true,true,Instant.now().minusSeconds(3600));
        assertThatThrownBy(()->access().requireReady(7L,b.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tokens.replaceTokenForCustomer("Sharekhan",123L,7L,"new-token",expiry);
        em.flush(); em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(expiry);
        assertThatCode(()->access().requireReady(7L,b.getId())).doesNotThrowAnyException();
        assertThat(tokenRows.findAllByBrokerNameAndBrokerCredentialsId("Sharekhan",b.getId())).hasSize(1);
    }
    @Test void credentialRefreshRenewsOnlyItsOwnConnection() {
        var b=broker(true,true,Instant.now().minusSeconds(3600));
        var other=broker(true,true,Instant.EPOCH);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(expiry);
        assertThat(connections.findById(other.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(Instant.EPOCH);
    }
    @Test void refreshCannotReconnectExplicitlyDisconnectedAccount() {
        var b=broker(true,true,Instant.EPOCH);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(()->access().requireReady(7L,b.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void refreshCannotCreateFirstTimeConnectionConsent() {
        var b=broker(true,true,null);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId())).isEmpty();
    }
    @Test void refreshDoesNotReactivateInactiveAccount() {
        var before=Instant.parse("2020-01-01T00:00:00Z");
        var b=broker(false,false,before);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(before);
        assertThat(brokers.findById(b.getId()).orElseThrow().getActive()).isFalse();
    }
    @Test void successfulRefreshPreservesTradingDisable() {
        var b=broker(true,false,Instant.parse("2020-01-01T00:00:00Z"));
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(expiry);
        assertThat(brokers.findById(b.getId()).orElseThrow().getTradingEnabled()).isFalse();
        assertThatThrownBy(()->access().requireReady(7L,b.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void expiredOrBlankTokenCannotRenewVerification() {
        var before=Instant.parse("2020-01-01T00:00:00Z");
        var b=broker(true,true,before);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"",expiry);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",Instant.now().minusSeconds(60));
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(before);
    }
    @Test void differentBrokerOrGlobalTokenCannotRenewVerification() {
        var before=Instant.parse("2020-01-01T00:00:00Z");
        var b=broker(true,true,before);
        tokens.replaceTokenForBrokerCredentials("Dhan",b.getId(),"new-token",expiry);
        tokens.replaceToken("Sharekhan","global-token",expiry);
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(before);
    }
    @Test void verificationMatchesShorterReplacementSession() {
        var b=broker(true,true,expiry);
        tokens.replaceTokenForBrokerCredentials("Sharekhan",b.getId(),"new-token",expiry.minusSeconds(3600));
        em.clear();
        assertThat(connections.findById(b.getId()).orElseThrow().getVerifiedUntil()).isEqualTo(expiry.minusSeconds(3600));
    }
}
