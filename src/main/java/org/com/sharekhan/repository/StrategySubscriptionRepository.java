package org.com.sharekhan.repository;

import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategySubscriptionRepository extends JpaRepository<StrategySubscriptionEntity, Long> {
    /** Evaluation may update its outcome, but cannot undo a concurrent cancellation or overwrite settings. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true,flushAutomatically=true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update StrategySubscriptionEntity s set s.status='ACTIVE', s.lastMessage=:#{#state.lastMessage}, s.lastEvaluationStatus=:#{#state.lastEvaluationStatus}, s.generatedTradeRequestId=:#{#state.generatedTradeRequestId}, s.lastEvaluatedAt=:#{#state.lastEvaluatedAt}, s.completedAt=:#{#state.completedAt}, s.updatedAt=:#{#state.lastEvaluatedAt} where s.id=:#{#state.id} and s.status in ('ACTIVE','TRIGGERED')")
    int recordEvaluationIfActive(@org.springframework.data.repository.query.Param("state") StrategySubscriptionEntity state);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from StrategySubscriptionEntity s where s.id=:id")
    java.util.Optional<StrategySubscriptionEntity> lockForRunEntry(@org.springframework.data.repository.query.Param("id") Long id);
    long countByAppUserIdAndStatusIn(Long appUserId, List<String> statuses);
    List<StrategySubscriptionEntity> findByStatusOrderByIdDesc(String status);
    List<StrategySubscriptionEntity> findByStatusInOrderByIdDesc(List<String> statuses);
    List<StrategySubscriptionEntity> findByAppUserIdAndStatusInOrderByIdDesc(Long appUserId, List<String> statuses);
    List<StrategySubscriptionEntity> findByAppUserIdOrderByIdDesc(Long appUserId);
    List<StrategySubscriptionEntity> findByStatusAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
            String status,
            String templateId,
            String symbol,
            Long appUserId
    );
    List<StrategySubscriptionEntity> findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
            List<String> statuses,
            String templateId,
            String symbol,
            Long appUserId
    );
}
