package org.com.sharekhan.audit;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.repository.TradeAuditEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Existing H2 implementation, retained until the PostgreSQL POC is explicitly enabled. */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.audit.postgres", name = "enabled", havingValue = "false", matchIfMissing = true)
public class H2AuditEventStore implements AuditEventStore {
    private final TradeAuditEventRepository repository;

    @Override
    public TradeAuditEventEntity save(TradeAuditEventEntity event) {
        return repository.save(event);
    }

    @Override
    public List<TradeAuditEventEntity> findTop200() {
        return repository.findTop200ByOrderByOccurredAtDesc();
    }

    @Override
    public List<TradeAuditEventEntity> findTop200ByAppUserId(Long appUserId) {
        return repository.findTop200ByAppUserIdOrderByOccurredAtDesc(appUserId);
    }

    @Override
    public List<TradeAuditEventEntity> findByTriggerRequestId(Long triggerRequestId) {
        return repository.findByTriggerRequestIdOrderByOccurredAtAsc(triggerRequestId);
    }

    @Override
    public List<TradeAuditEventEntity> findByTradeId(Long tradeId) {
        return repository.findByTradeIdOrderByOccurredAtAsc(tradeId);
    }
}
