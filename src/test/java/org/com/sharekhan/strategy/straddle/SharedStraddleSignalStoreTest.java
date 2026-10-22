package org.com.sharekhan.strategy.straddle;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.SharedStraddleSignal;
import org.com.sharekhan.repository.SharedStraddleSignalRepository;
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
class SharedStraddleSignalStoreTest {
    @Autowired SharedStraddleSignalRepository repository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void clean(){repository.deleteAll();}
    SharedStraddleSignal row(double strike) {
        return SharedStraddleSignal.builder().id("shared").tradingDay(LocalDate.of(2026,10,22)).symbol("SENSEX").slot("PM")
                .expiry("22/10/2026").strike(strike).ceScripCode(1).peScripCode(2).lotSize(20).spotPrice(73164.81)
                .spotObservedAt(LocalDateTime.of(2026,10,22,13,30)).createdAt(LocalDateTime.of(2026,10,22,13,30)).build();
    }
    @Test void committedSelectionSurvivesRestartAndCannotChangeForLaterSubscribers() {
        var first=new SharedStraddleSignalStore(repository,em,manager).loadOrCreate("shared",()->row(73200));
        var afterRestart=new SharedStraddleSignalStore(repository,em,manager).loadOrCreate("shared",()->{throw new AssertionError("must not recalculate");});
        assertThat(afterRestart.getStrike()).isEqualTo(first.getStrike()).isEqualTo(73200);
        assertThat(repository.count()).isOne();
    }
    @Test void competingProcessesReadOneCommittedWinner() throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();return new SharedStraddleSignalStore(repository,em,manager).loadOrCreate("shared",()->row(73200));});
            var b=pool.submit(()->{start.await();return new SharedStraddleSignalStore(repository,em,manager).loadOrCreate("shared",()->row(73300));});
            start.countDown();assertThat(a.get(10,TimeUnit.SECONDS).getStrike()).isEqualTo(b.get(10,TimeUnit.SECONDS).getStrike());
            assertThat(repository.count()).isOne();
        } finally {pool.shutdownNow();}
    }
}
