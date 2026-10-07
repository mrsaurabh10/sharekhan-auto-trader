package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TriggerTradeRequestRepositoryCleanupTest {

    @Autowired
    private TriggerTradeRequestRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void deletesStaleIntradayRequestsRegardlessOfStatusWhilePreservingFutureRequests() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 23, 15, 30);
        LocalDateTime stale = cutoff.minusDays(3);
        save(true, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, stale);
        save(true, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, null);
        save(true, null, stale);
        save(false, TriggeredTradeStatus.TRIGGERED, stale);
        Long positional = save(false, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, stale);
        Long unspecified = save(null, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, stale);
        Long atCutoff = save(true, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, cutoff);
        Long afterCutoff = save(true, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, cutoff.plusMinutes(1));

        int deleted = repository.deleteStaleRequestsCreatedBeforePreservingNonIntradayStatus(
                cutoff, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        entityManager.clear();

        assertThat(deleted).isEqualTo(4);
        assertThat(repository.findAll()).extracting(TriggerTradeRequestEntity::getId)
                .containsExactlyInAnyOrder(positional, unspecified, atCutoff, afterCutoff);
    }

    private Long save(Boolean intraday, TriggeredTradeStatus status, LocalDateTime createdAt) {
        return repository.saveAndFlush(TriggerTradeRequestEntity.builder()
                .symbol("ABCAPITAL")
                .intraday(intraday)
                .status(status)
                .createdAt(createdAt)
                .build()).getId();
    }
}
