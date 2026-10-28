package org.com.sharekhan.repository;
import org.com.sharekhan.entity.StrategyRun;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

public interface StrategyRunRepository extends JpaRepository<StrategyRun,String> {
    Page<StrategyRun> findByAppUserIdOrderByCreatedAtDesc(Long userId,Pageable page);
    Page<StrategyRun> findAllByOrderByCreatedAtDesc(Pageable page);
    List<StrategyRun> findByStatusIn(List<String> statuses);
    List<StrategyRun> findByTemplateIdAndStatusIn(String templateId,List<String> statuses);
    List<StrategyRun> findByAppUserIdAndBrokerCredentialsIdAndTemplateIdAndTradingDayOrderByCreatedAtAsc(Long userId,Long brokerId,String templateId,LocalDate day);
    @Modifying(clearAutomatically=true,flushAutomatically=true) @Transactional
    @Query("update StrategyRun r set r.message=:message where r.id=:id and r.status='OPEN' and (r.message is null or r.message<>:message)")
    int recordMessage(@Param("id") String id,@Param("message") String message);
    List<StrategyRun> findBySubscriptionIdAndStatusIn(Long subscriptionId,List<String> statuses);
    @Modifying(clearAutomatically=true,flushAutomatically=true) @Transactional
    @Query("update StrategyRun r set r.status='CLOSING',r.closeRequestedAt=:now,r.exitReason=:reason,r.message='Closing positions; waiting for broker confirmation' where r.id=:id and r.status='OPEN'")
    int requestClose(@Param("id") String id,@Param("reason") String reason,@Param("now") LocalDateTime now);
    @Modifying(clearAutomatically=true,flushAutomatically=true) @Transactional
    @Query("update StrategyRun r set r.peakPnl=:peak,r.stopFloor=:floor where r.id=:id and r.status='OPEN' and (r.peakPnl is null or r.peakPnl<=:peak) and (r.stopFloor is null or r.stopFloor<=:floor)")
    int advanceRisk(@Param("id") String id,@Param("peak") BigDecimal peak,@Param("floor") BigDecimal floor);
    @Modifying(clearAutomatically=true,flushAutomatically=true) @Transactional
    @Query("update StrategyRun r set r.status='CLOSED',r.closedAt=:now,r.message='All entries resolved and positions closed' where r.id=:id and r.status='CLOSING'")
    int markClosed(@Param("id") String id,@Param("now") LocalDateTime now);
}
