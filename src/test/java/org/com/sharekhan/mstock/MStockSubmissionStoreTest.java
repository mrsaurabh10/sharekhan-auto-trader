package org.com.sharekhan.mstock;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties="spring.jpa.hibernate.ddl-auto=create-drop")
@Import(MStockSubmissionStore.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MStockSubmissionStoreTest {
    @Autowired MStockSubmissionStore store;
    @Autowired PlatformTransactionManager transactions;
    @Test void claimSurvivesRollbackAndRecordsRefusal() {
        new TransactionTemplate(transactions).execute(status->{ store.claim("MSrollback",7L,"EXIT:88",1);status.setRollbackOnly();return null; });
        assertThat(store.latest(7L,"EXIT:88")).isPresent();
        assertThatThrownBy(()->store.claim("MSdifferent",7L,"EXIT:88",1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        store.accepted("MSrollback","12345");store.inactive("MSrollback","REFUSED");
        assertThat(store.latest(7L,"EXIT:88").orElseThrow().orderId()).isEqualTo("12345");
        assertThat(store.latest(7L,"EXIT:88").orElseThrow().outcome()).isEqualTo("REFUSED");
    }
    @Test void competingWorkersOnlyPermitOneSender() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> work=()->{start.await();try{store.claim("MSconcurrent",8L,"ENTRY:99",1);return true;}
                catch(org.springframework.dao.DataIntegrityViolationException duplicate){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
}
