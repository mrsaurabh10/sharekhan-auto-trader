package org.com.sharekhan.repository;

import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategySubscriptionRepository extends JpaRepository<StrategySubscriptionEntity, Long> {
    List<StrategySubscriptionEntity> findByStatusOrderByIdDesc(String status);
    List<StrategySubscriptionEntity> findByAppUserIdOrderByIdDesc(Long appUserId);
    List<StrategySubscriptionEntity> findByStatusAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
            String status,
            String templateId,
            String symbol,
            Long appUserId
    );
}
