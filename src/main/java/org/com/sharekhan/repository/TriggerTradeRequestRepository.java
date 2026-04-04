package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("select distinct t.appUserId from TriggerTradeRequestEntity t where t.appUserId is not null")
    List<Long> findDistinctAppUserIds();

    List<TriggerTradeRequestEntity> findBySymbolAndAppUserIdAndStatus(String symbol, Long appUserId, TriggeredTradeStatus status);

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
