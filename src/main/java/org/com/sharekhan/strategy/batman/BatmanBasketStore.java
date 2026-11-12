package org.com.sharekhan.strategy.batman;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.BatmanBasket;
import org.com.sharekhan.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
@Service
public class BatmanBasketStore{
    private final BatmanBasketRepository baskets;private final StrategyRunRepository runs;private final EntityManager em;private final TransactionTemplate tx;
    private final AppUserRepository users;private final TriggeredTradeSetupRepository trades;private final TriggerTradeRequestRepository requests;
    public BatmanBasketStore(BatmanBasketRepository baskets,StrategyRunRepository runs,EntityManager em,PlatformTransactionManager manager,
            AppUserRepository users,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests){
        this.baskets=baskets;this.runs=runs;this.em=em;this.users=users;this.trades=trades;this.requests=requests;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String entryBlockReason(Long user,Long broker,String symbol,LocalDate day){
        var history=baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(user,broker,symbol);
        org.com.sharekhan.entity.StrategyRun latest=null;
        for(var b:history){
            var run=runs.findById(b.getStrategyRunId()).orElse(null);
            if(run==null||!Objects.equals(run.getAppUserId(),user)||!Objects.equals(run.getBrokerCredentialsId(),broker)||!Objects.equals(run.getSymbol(),symbol)||!BatmanRules.TEMPLATE.equals(run.getTemplateId())
                    ||!BatmanReentryRules.confirmedFlat(run,trades.findByStrategyRunId(run.getId()),requests.findByStrategyRunId(run.getId())))
                return "Prior Batman basket has open or unresolved exposure; re-entry is blocked";
            if(!run.getClosedAt().toLocalDate().isBefore(day))return "Prior basket completed today; waiting until the next trading day";
            if(!b.getClaimedAt().toLocalDate().isBefore(day))return "A Batman basket was already attempted today; no same-day re-entry";
            if(latest==null)latest=run;
        }
        return latest==null?null:BatmanReentryRules.cooldown(history.getFirst(),latest,day);
    }
    public boolean claim(BatmanBasket basket){
        if(baskets.existsById(basket.getId()))return false;
        try{return Boolean.TRUE.equals(tx.execute(t->{
            users.lockForEnrollment(basket.getAppUserId()).orElseThrow(()->new IllegalArgumentException("Account not found"));
            // Serialize account-scoped admission across processes and re-read all exposure after taking the lock.
            if(baskets.existsById(basket.getId())||entryBlockReason(basket.getAppUserId(),basket.getBrokerCredentialsId(),basket.getSymbol(),basket.getClaimedAt().toLocalDate())!=null)return false;
            em.persist(basket);em.flush();return true;
        }));}
        catch(RuntimeException e){if(baskets.existsById(basket.getId()))return false;throw e;}
    }
    public void arm(BatmanBasket basket,BigDecimal credit){tx.executeWithoutResult(t->{
        if(baskets.arm(basket.getId(),credit)==1&&runs.armBatmanRisk(basket.getStrategyRunId(),credit,org.com.sharekhan.strategy.run.StrategyRiskSettings.AmountMode.TOTAL)!=1)throw new IllegalStateException("Basket run is closing; risk arming cancelled");
    });}
}
