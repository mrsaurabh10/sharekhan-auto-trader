package org.com.sharekhan.audit;

import org.com.sharekhan.entity.TradeAuditEventEntity;

import java.util.List;

/** Persistence boundary used while trade-audit history is moved independently of H2. */
public interface AuditEventStore {
    TradeAuditEventEntity save(TradeAuditEventEntity event);

    List<TradeAuditEventEntity> findTop200();

    List<TradeAuditEventEntity> findTop200ByAppUserId(Long appUserId);

    List<TradeAuditEventEntity> findByTriggerRequestId(Long triggerRequestId);

    List<TradeAuditEventEntity> findByTradeId(Long tradeId);
}
