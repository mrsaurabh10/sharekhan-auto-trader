package org.com.sharekhan.repository;
import org.com.sharekhan.entity.BatmanBasket;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
public interface BatmanBasketRepository extends JpaRepository<BatmanBasket,String>{
    List<BatmanBasket> findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(Long userId,Long brokerId,String symbol);
    Optional<BatmanBasket> findByStrategyRunId(String runId);
    List<BatmanBasket> findByStateIn(List<String> states);
    /** Cheap poll filter. Full confirmed-flat/request reconciliation still runs before any claim. */
    @Query("select count(b)>0 from BatmanBasket b left join StrategyRun r on r.id=b.strategyRunId where b.appUserId=:user and b.brokerCredentialsId=:broker and b.symbol=:symbol and (b.claimedAt>=:dayStart or b.state<>'CLOSED' or r.id is null or r.status<>'CLOSED' or r.closedAt is null or r.closedAt>=:dayStart)")
    boolean hasDailyEntryBlocker(@Param("user")Long user,@Param("broker")Long broker,@Param("symbol")String symbol,@Param("dayStart")java.time.LocalDateTime dayStart);
    @Modifying @Transactional
    @Query("update BatmanBasket b set b.state=:state,b.message=:message where b.id=:id and b.state<>'CLOSED'")
    int outcome(@Param("id")String id,@Param("state")String state,@Param("message")String message);
    @Modifying @Transactional
    @Query("update BatmanBasket b set b.centerCredit=:credit,b.state='ARMED',b.message='Four confirmed legs; center-credit TP and equal SL armed' where b.id=:id and b.state='ENTERING'")
    int arm(@Param("id")String id,@Param("credit")BigDecimal credit);
}
