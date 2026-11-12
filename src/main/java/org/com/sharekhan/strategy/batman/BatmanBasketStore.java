package org.com.sharekhan.strategy.batman;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.BatmanBasket;
import org.com.sharekhan.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
@Service
public class BatmanBasketStore{
    private final BatmanBasketRepository baskets;private final StrategyRunRepository runs;private final EntityManager em;private final TransactionTemplate tx;
    public BatmanBasketStore(BatmanBasketRepository baskets,StrategyRunRepository runs,EntityManager em,PlatformTransactionManager manager){this.baskets=baskets;this.runs=runs;this.em=em;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public boolean claim(BatmanBasket basket){
        if(baskets.existsById(basket.getId()))return false;
        try{tx.executeWithoutResult(t->{em.persist(basket);em.flush();});return true;}
        catch(RuntimeException e){if(baskets.existsById(basket.getId()))return false;throw e;}
    }
    public void arm(BatmanBasket basket,BigDecimal credit){tx.executeWithoutResult(t->{
        if(baskets.arm(basket.getId(),credit)==1&&runs.armBatmanRisk(basket.getStrategyRunId(),credit,org.com.sharekhan.strategy.run.StrategyRiskSettings.AmountMode.TOTAL)!=1)throw new IllegalStateException("Basket run is closing; risk arming cancelled");
    });}
}
