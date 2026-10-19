package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BrokerExecutionProductPersistenceTest {
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired EntityManager entityManager;

    @Test void restartRetainsDeliveryProductIndependentlyOfSameDayExitAndPreservesLegacyUnknown() {
        var current=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().intraday(true).brokerExecutionProduct("CNC").build());
        var legacy=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().intraday(true).build());
        entityManager.clear();
        var reloaded=trades.findById(current.getId()).orElseThrow();
        assertThat(reloaded.getBrokerExecutionProduct()).isEqualTo("CNC");
        assertThat(reloaded.getIntraday()).isTrue();
        assertThat(trades.findById(legacy.getId()).orElseThrow().getBrokerExecutionProduct()).isNull();
    }
}
