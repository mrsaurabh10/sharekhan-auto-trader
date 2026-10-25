package org.com.sharekhan.dhan;

import org.com.sharekhan.entity.DhanOrderSubmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop")
@Import(DhanSubmissionClaim.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class DhanSubmissionClaimTest {
    @Autowired DhanSubmissionClaim claims;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Test void coverJournalPersistsBuySideAndDoesNotMistakeSellEntryForExit() {
        claims.claim("short-entry",9L,"TRADE:88","SELL");
        assertThat(claims.latestExit(9L,88L,"BUY")).isEmpty();
        new TransactionTemplate(transactions).execute(status->{
            claims.claimExit("cover-buy",9L,"TRADE:88:EXIT:1",1,"BUY");status.setRollbackOnly();return null;
        });
        assertThat(claims.latestExit(9L,88L,"BUY")).get().extracting(DhanSubmissionClaim.ExitAttempt::correlation).isEqualTo("cover-buy");
        assertThat(jdbc.queryForObject("SELECT order_side FROM dhan_order_submissions WHERE correlation_id='cover-buy'",String.class)).isEqualTo("BUY");
        assertThatThrownBy(()->claims.claimExit("cover-buy",9L,"TRADE:88:EXIT:1",1,"BUY")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        claims.exitOutcome("cover-buy","REFUSED","BUY");
        assertThat(claims.latestExit(9L,88L,"BUY")).get().extracting(DhanSubmissionClaim.ExitAttempt::outcome).isEqualTo("REFUSED");
    }

    @Test void claimSurvivesCallerRollbackAndRejectsDuplicateAfterward() {
        new TransactionTemplate(transactions).execute(status->{
            claims.claim("rollback-proof",1L,"TRADE:2","BUY");status.setRollbackOnly();return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dhan_order_submissions WHERE correlation_id='rollback-proof'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->claims.claim("rollback-proof",1L,"TRADE:2","BUY")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void competingWorkersCanOnlyClaimOnePost() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> work=()->{start.await();try {claims.claim("concurrent-proof",1L,"TRADE:3","SELL");return true;}catch(org.springframework.dao.DataIntegrityViolationException duplicate){return false;}};
            var first=pool.submit(work);var second=pool.submit(work);start.countDown();
            assertThat(java.util.List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
}
