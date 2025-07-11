package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TriggeredTradeSetupRepository extends JpaRepository<TriggeredTradeSetupEntity, Long> {

    List<TriggeredTradeSetupEntity> findByStatus(TriggeredTradeStatus status);

    List<TriggeredTradeSetupEntity> findByScripCodeAndStatus(Integer scripCode, TriggeredTradeStatus status);

    Optional<TriggeredTradeSetupEntity> findByOrderId(String orderId);

    Optional<TriggeredTradeSetupEntity> findByExitOrderId(String exitOrderId);

}
