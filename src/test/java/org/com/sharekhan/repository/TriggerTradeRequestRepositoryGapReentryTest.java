package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TriggerTradeRequestRepositoryGapReentryTest {

    @Autowired
    private TriggerTradeRequestRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void atomicallyRearmsGapFillRequestOnlyOnce() {
        TriggerTradeRequestEntity request = repository.saveAndFlush(TriggerTradeRequestEntity.builder()
                .symbol("TEST")
                .gapProtectionEnabled(true)
                .gapReentryCount(0)
                .status(TriggeredTradeStatus.TRIGGERED)
                .build());

        int firstAttempt = repository.rearmGapFillOnce(request.getId());
        int repeatedAttempt = repository.rearmGapFillOnce(request.getId());
        entityManager.clear();

        TriggerTradeRequestEntity reloaded = repository.findById(request.getId()).orElseThrow();
        assertThat(firstAttempt).isEqualTo(1);
        assertThat(repeatedAttempt).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(reloaded.getGapReentryCount()).isEqualTo(1);
        assertThat(reloaded.getOpeningRuleReset()).isTrue();
    }
}
