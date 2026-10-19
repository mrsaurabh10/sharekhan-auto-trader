package org.com.sharekhan.strategy.straddle;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.ExpiryStraddleSession;
import org.com.sharekhan.repository.ExpiryStraddleSessionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop",showSql=false)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ExpiryStraddleSessionStoreTest {
    @Autowired ExpiryStraddleSessionRepository repository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void clean(){repository.deleteAll();}
    ExpiryStraddleSession row(String id) {
        return ExpiryStraddleSession.builder().id(id).appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY")
                .tradingDay(LocalDate.of(2026,10,13)).slot("AM").exitAt(LocalDateTime.of(2026,10,13,10,30))
                .strike(25000).lots(1).expectedQuantity(65).claimedAt(LocalDateTime.of(2026,10,13,9,30)).build();
    }
    @Test void claimSurvivesStoreRecreationAndCannotBeOverwritten() {
        assertThat(new ExpiryStraddleSessionStore(repository,em,manager).claim(row("session"))).isTrue();
        var duplicate=row("session");duplicate.setStrike(26000);
        assertThat(new ExpiryStraddleSessionStore(repository,em,manager).claim(duplicate)).isFalse();
        assertThat(repository.findById("session").orElseThrow().getStrike()).isEqualTo(25000);
        assertThat(repository.findById("session").orElseThrow().getExpectedQuantity()).isEqualTo(65);
    }
    @Test void competingInstancesCanOnlyClaimOneEntry() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim=()->{start.await();return new ExpiryStraddleSessionStore(repository,em,manager).claim(row("concurrent"));};
            var first=pool.submit(claim);var second=pool.submit(claim);start.countDown();
            assertThat(java.util.List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(repository.count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
}
