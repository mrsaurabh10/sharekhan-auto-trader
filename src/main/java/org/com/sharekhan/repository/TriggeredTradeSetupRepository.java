package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TriggeredTradeSetupRepository extends JpaRepository<TriggeredTradeSetupEntity, Long> {

    List<TriggeredTradeSetupEntity> findByStatus(TriggeredTradeStatus status);

    List<TriggeredTradeSetupEntity> findByScripCodeAndStatus(Integer scripCode, TriggeredTradeStatus status);

    Optional<TriggeredTradeSetupEntity> findByOrderId(String orderId);

    Optional<TriggeredTradeSetupEntity> findByExitOrderId(String exitOrderId);

    List<TriggeredTradeSetupEntity> findByIntradayTrueAndStatus(TriggeredTradeStatus status);

    List<TriggeredTradeSetupEntity> findTop10ByOrderByIdDesc();

    // Atomically claim the exit flow by setting status and exit_reason only if current status is not already in an exiting/terminal state.
    // Using native SQL to avoid JPQL symbol resolution issues; pass status names (String) for newStatus and forbids.
    @Modifying
    @Transactional
    @Query(value = "UPDATE triggered_trade_setups SET status = :newStatus, exit_reason = :exitReason WHERE id = :id AND status NOT IN (:forbid1, :forbid2, :forbid3)", nativeQuery = true)
    int claimExitIfNotAlready(@Param("id") Long id,
                               @Param("newStatus") String newStatus,
                               @Param("exitReason") String exitReason,
                               @Param("forbid1") String forbid1,
                               @Param("forbid2") String forbid2,
                               @Param("forbid3") String forbid3);

    // Stricter claim: only update status when current status equals expectedStatus. Returns 1 when transition succeeded.
    @Modifying
    @Transactional
    @Query(value = "UPDATE triggered_trade_setups SET status = :newStatus, exit_reason = :exitReason WHERE id = :id AND status = :expectedStatus", nativeQuery = true)
    int claimIfStatusEquals(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus,
                            @Param("exitReason") String exitReason);

    @Modifying
    @Transactional
    @Query(value = "UPDATE triggered_trade_setups SET exit_order_id = :exitOrderId WHERE id = :id", nativeQuery = true)
    int setExitOrderId(@Param("id") Long id, @Param("exitOrderId") String exitOrderId);

}
