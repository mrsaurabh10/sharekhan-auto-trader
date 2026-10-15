package org.com.sharekhan.onboarding;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.datasource.url=jdbc:h2:mem:onboarding;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("onboarding-test")
class AccountPersistenceTest {
    @Autowired AccountProfileRepository profiles;
    @Autowired BrokerConnectionRepository connections;
    @Autowired AppUserRepository users;
    @Autowired org.com.sharekhan.repository.TriggerTradeRequestRepository requests;
    @Autowired org.com.sharekhan.repository.TriggeredTradeSetupRepository trades;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;
    @Test void storesAccountAndFindsHashedTokenUnderTransactionLock() {
        AppUser user=new AppUser(); user.setUsername("alex@example.test"); user.setPassword("hash"); users.saveAndFlush(user);
        AccountProfile p=new AccountProfile();p.setUserId(user.getId());p.setEmail(user.getUsername());p.setDisplayName("Alex");p.setCreatedAt(Instant.now());
        p.setTokenHash(AccountService.hash("a".repeat(43)));p.setTokenPurpose("VERIFY");p.setTokenExpiresAt(Instant.now().plusSeconds(1800));profiles.saveAndFlush(p);
        assertThat(profiles.findByTokenHash(AccountService.hash("a".repeat(43)))).isPresent();
        assertThat(profiles.findByEmail("alex@example.test")).isPresent();
    }
    @Test void cancellationCannotRemoveAnotherUsersRequestOrBrokerAcceptedEntry() {
        var request=org.com.sharekhan.entity.TriggerTradeRequestEntity.builder().appUserId(7L)
            .status(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build();
        requests.saveAndFlush(request);
        assertThat(requests.deleteUnsubmittedRequest(request.getId(),99L,java.util.List.of(request.getStatus()))).isZero();
        var trade=org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().triggerRequestId(request.getId()).build();
        trades.saveAndFlush(trade);
        assertThat(requests.deleteUnsubmittedRequest(request.getId(),7L,java.util.List.of(request.getStatus()))).isZero();
    }
    @Test void cancellationDeletesUnclaimedOwnedRequestAtomically() {
        var request=org.com.sharekhan.entity.TriggerTradeRequestEntity.builder().appUserId(7L)
            .status(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION).build();
        requests.saveAndFlush(request);
        assertThat(requests.deleteUnsubmittedRequest(request.getId(),7L,java.util.List.of(request.getStatus()))).isOne();
        em.clear();assertThat(requests.findById(request.getId())).isEmpty();
    }
    @Test void brokerVerificationSurvivesRepositoryReload() {
        var connection=new BrokerConnection();connection.setCredentialId(99L);connection.setVerifiedUntil(Instant.now().plusSeconds(3600));
        connections.saveAndFlush(connection);
        assertThat(connections.findById(99L)).get().extracting(BrokerConnection::getVerifiedUntil).isNotNull();
    }
}
