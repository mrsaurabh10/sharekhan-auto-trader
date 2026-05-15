package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface TriggerTradeRequestRepository extends JpaRepository<TriggerTradeRequestEntity, Long> {

    List<TriggerTradeRequestEntity> findByStatus(TriggeredTradeStatus status);

    List<TriggerTradeRequestEntity> findByScripCodeAndStatus(Integer scripCode, TriggeredTradeStatus status);
    
    List<TriggerTradeRequestEntity> findBySpotScripCodeAndStatus(Integer spotScripCode, TriggeredTradeStatus status);

    List<TriggerTradeRequestEntity>  findTop10ByOrderByIdDesc();

    // app-user-scoped recent requests
    List<TriggerTradeRequestEntity> findTop10ByAppUserIdOrderByIdDesc(Long appUserId);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                       OR LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    ORDER BY r.id DESC
                    LIMIT 10
                    """,
            nativeQuery = true
    )
    List<TriggerTradeRequestEntity> findTop10ByAppUserIdOrSimulatorOrderByIdDesc(
            @Param("appUserId") Long appUserId,
            @Param("simulatorBrokerName") String simulatorBrokerName);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                      AND (b.broker_name IS NULL OR LOWER(b.broker_name) <> LOWER(:simulatorBrokerName))
                    ORDER BY r.id DESC
                    LIMIT 10
                    """,
            nativeQuery = true
    )
    List<TriggerTradeRequestEntity> findTop10ByAppUserIdExcludingSimulatorOrderByIdDesc(
            @Param("appUserId") Long appUserId,
            @Param("simulatorBrokerName") String simulatorBrokerName);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    ORDER BY r.id DESC
                    LIMIT 10
                    """,
            nativeQuery = true
    )
    List<TriggerTradeRequestEntity> findTop10BySimulatorOrderByIdDesc(
            @Param("simulatorBrokerName") String simulatorBrokerName);

    @Query(
            value = """
                    SELECT *
                    FROM trigger_trade_requests
                    ORDER BY CASE status
                        WHEN 'PLACED_ORDER_PENDING' THEN 0
                        WHEN 'PLACED_PENDING_CONFIRMATION' THEN 0
                        WHEN 'TRIGGERED' THEN 1
                        WHEN 'REJECTED' THEN 2
                        ELSE 3
                    END, id DESC
                    """,
            countQuery = "SELECT COUNT(*) FROM trigger_trade_requests",
            nativeQuery = true
    )
    Page<TriggerTradeRequestEntity> findRequestsOrderedForDashboard(Pageable pageable);

    @Query(
            value = """
                    SELECT *
                    FROM trigger_trade_requests
                    WHERE app_user_id = :appUserId
                    ORDER BY CASE status
                        WHEN 'PLACED_ORDER_PENDING' THEN 0
                        WHEN 'PLACED_PENDING_CONFIRMATION' THEN 0
                        WHEN 'TRIGGERED' THEN 1
                        WHEN 'REJECTED' THEN 2
                        ELSE 3
                    END, id DESC
                    """,
            countQuery = "SELECT COUNT(*) FROM trigger_trade_requests WHERE app_user_id = :appUserId",
            nativeQuery = true
    )
    Page<TriggerTradeRequestEntity> findRequestsOrderedForDashboardByAppUserId(@Param("appUserId") Long appUserId, Pageable pageable);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                      AND (b.broker_name IS NULL OR LOWER(b.broker_name) <> LOWER(:simulatorBrokerName))
                    ORDER BY CASE r.status
                        WHEN 'PLACED_ORDER_PENDING' THEN 0
                        WHEN 'PLACED_PENDING_CONFIRMATION' THEN 0
                        WHEN 'TRIGGERED' THEN 1
                        WHEN 'REJECTED' THEN 2
                        ELSE 3
                    END, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                      AND (b.broker_name IS NULL OR LOWER(b.broker_name) <> LOWER(:simulatorBrokerName))
                    """,
            nativeQuery = true
    )
    Page<TriggerTradeRequestEntity> findRequestsOrderedForDashboardByAppUserIdExcludingSimulator(
            @Param("appUserId") Long appUserId,
            @Param("simulatorBrokerName") String simulatorBrokerName,
            Pageable pageable);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                       OR LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    ORDER BY CASE r.status
                        WHEN 'PLACED_ORDER_PENDING' THEN 0
                        WHEN 'PLACED_PENDING_CONFIRMATION' THEN 0
                        WHEN 'TRIGGERED' THEN 1
                        WHEN 'REJECTED' THEN 2
                        ELSE 3
                    END, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE r.app_user_id = :appUserId
                       OR LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    """,
            nativeQuery = true
    )
    Page<TriggerTradeRequestEntity> findRequestsOrderedForDashboardByAppUserIdOrSimulator(
            @Param("appUserId") Long appUserId,
            @Param("simulatorBrokerName") String simulatorBrokerName,
            Pageable pageable);

    @Query(
            value = """
                    SELECT r.*
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    ORDER BY CASE r.status
                        WHEN 'PLACED_ORDER_PENDING' THEN 0
                        WHEN 'PLACED_PENDING_CONFIRMATION' THEN 0
                        WHEN 'TRIGGERED' THEN 1
                        WHEN 'REJECTED' THEN 2
                        ELSE 3
                    END, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM trigger_trade_requests r
                    LEFT JOIN broker_credentials b ON b.id = r.broker_credentials_id
                    WHERE LOWER(b.broker_name) = LOWER(:simulatorBrokerName)
                    """,
            nativeQuery = true
    )
    Page<TriggerTradeRequestEntity> findRequestsOrderedForDashboardBySimulator(
            @Param("simulatorBrokerName") String simulatorBrokerName,
            Pageable pageable);

    @Query("select distinct t.appUserId from TriggerTradeRequestEntity t where t.appUserId is not null")
    List<Long> findDistinctAppUserIds();

    List<TriggerTradeRequestEntity> findBySymbolAndAppUserIdAndStatus(String symbol, Long appUserId, TriggeredTradeStatus status);

    List<TriggerTradeRequestEntity> findBySymbolAndAppUserIdAndStatusIn(String symbol, Long appUserId, List<TriggeredTradeStatus> statuses);

    @Query("select r from TriggerTradeRequestEntity r where lower(r.symbol) = lower(:symbol) and r.status in :statuses")
    List<TriggerTradeRequestEntity> findBySymbolIgnoreCaseAndStatusIn(@Param("symbol") String symbol,
                                                                      @Param("statuses") List<TriggeredTradeStatus> statuses);

    // Added for duplicate check
    List<TriggerTradeRequestEntity> findBySymbolAndStrikePriceAndOptionTypeAndAppUserIdAndStatus(String symbol, Double strikePrice, String optionType, Long appUserId, TriggeredTradeStatus status);

    // Stricter claim: only update status when current status equals expectedStatus. Returns 1 when transition succeeded.
    @Modifying
    @Transactional
    @Query(value = "UPDATE trigger_trade_requests SET status = :newStatus WHERE id = :id AND status = :expectedStatus", nativeQuery = true)
    int claimIfStatusEquals(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus);
}
