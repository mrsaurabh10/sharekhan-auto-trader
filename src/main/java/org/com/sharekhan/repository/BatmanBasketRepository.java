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
    @Modifying @Transactional
    @Query("update BatmanBasket b set b.state=:state,b.message=:message where b.id=:id and b.state<>'CLOSED'")
    int outcome(@Param("id")String id,@Param("state")String state,@Param("message")String message);
    @Modifying @Transactional
    @Query("update BatmanBasket b set b.centerCredit=:credit,b.state='ARMED',b.message='Four confirmed legs; center-credit TP and equal SL armed' where b.id=:id and b.state='ENTERING'")
    int arm(@Param("id")String id,@Param("credit")BigDecimal credit);
}
