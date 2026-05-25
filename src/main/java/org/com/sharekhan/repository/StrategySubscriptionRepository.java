package org.com.sharekhan.repository;

import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategySubscriptionRepository extends JpaRepository<StrategySubscriptionEntity, Long> {
    List<StrategySubscriptionEntity> findByStatusOrderByIdDesc(String status);
    List<StrategySubscriptionEntity> findByStatusInOrderByIdDesc(List<String> statuses);
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
