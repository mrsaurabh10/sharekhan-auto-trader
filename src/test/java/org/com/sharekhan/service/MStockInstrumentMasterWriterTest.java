package org.com.sharekhan.service;

import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MStockInstrumentMasterWriterTest {
    @Test
    void failedLaterBatchRollsBackDeletionAndEarlierBatches() {
        try (var factory = new Configuration().addAnnotatedClass(MStockInstrumentEntity.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:mstock-writer;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false").buildSessionFactory()) {
            var manager = new JpaTransactionManager(factory);
            var tx = new TransactionTemplate(manager);
            var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            var repository = new JpaRepositoryFactory(em).getRepository(MStockInstrumentRepository.class);
            var writer = new MStockInstrumentMasterWriter(repository);
            ReflectionTestUtils.setField(writer, "entityManager", em);
            var proxy = new ProxyFactory(writer);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var transactional = (MStockInstrumentMasterWriter) proxy.getProxy();
            tx.executeWithoutResult(status -> repository.saveAndFlush(row(1, "NFO:OLD")));
            var rows = new ArrayList<MStockInstrumentEntity>();
            for (int i = 2; i < 1002; i++) rows.add(row(i, "NFO:NEW" + i));
            rows.add(row(1002, "NFO:NEW2")); // Conflicting key after first batch was flushed.
            assertThatThrownBy(() -> transactional.replace(rows)).isInstanceOf(RuntimeException.class);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findByInstrumentKey("NFO:OLD")).isPresent();
            transactional.replace(List.of(row(2, "NFO:VALID")));
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findByInstrumentKey("NFO:VALID")).isPresent();
            assertThat(repository.findByInstrumentKey("NFO:OLD")).isEmpty();
        }
    }

    private MStockInstrumentEntity row(long id, String key) {
        return MStockInstrumentEntity.builder().instrumentToken(id).instrumentKey(key)
                .tradingSymbol(key.substring(4)).exchange("NFO").fetchedAt(LocalDateTime.now()).build();
    }
}
