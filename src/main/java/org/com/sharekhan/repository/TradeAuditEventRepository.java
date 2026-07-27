package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeAuditEventRepository extends JpaRepository<TradeAuditEventEntity, Long> {
    List<TradeAuditEventEntity> findTop200ByOrderByOccurredAtDesc();
    List<TradeAuditEventEntity> findTop200ByAppUserIdOrderByOccurredAtDesc(Long appUserId);
    List<TradeAuditEventEntity> findByTriggerRequestIdOrderByOccurredAtAsc(Long triggerRequestId);
    List<TradeAuditEventEntity> findByTradeIdOrderByOccurredAtAsc(Long tradeId);
}
