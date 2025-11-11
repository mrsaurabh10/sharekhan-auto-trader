package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TriggerTradeRequestRepository extends JpaRepository<TriggerTradeRequestEntity, Long> {

    List<TriggerTradeRequestEntity> findByStatus(TriggeredTradeStatus status);

    List<TriggerTradeRequestEntity> findByScripCodeAndStatus(Integer scripCode, TriggeredTradeStatus status);

    List<TriggerTradeRequestEntity>  findTop10ByOrderByIdDesc();

    // app-user-scoped recent requests
    List<TriggerTradeRequestEntity> findTop10ByAppUserIdOrderByIdDesc(Long appUserId);

    @Query("select distinct t.appUserId from TriggerTradeRequestEntity t where t.appUserId is not null")
    List<Long> findDistinctAppUserIds();
}
