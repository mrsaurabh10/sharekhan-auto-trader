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
    /** One extra, auditable attempt after today's original DAY basket aborted and is confirmed flat. */
    public String retryBlockReason(Long user,Long broker,String symbol,LocalDate day,String priorRunId,Long subscription){
        var history=baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(user,broker,symbol);
        BatmanBasket original=null;int today=0;
        for(var b:history){
            var run=runs.findById(b.getStrategyRunId()).orElse(null);
            if(run==null||!Objects.equals(run.getAppUserId(),user)||!Objects.equals(run.getBrokerCredentialsId(),broker)||!Objects.equals(run.getSymbol(),symbol)||!BatmanRules.TEMPLATE.equals(run.getTemplateId())
                    ||!BatmanReentryRules.confirmedFlat(run,trades.findByStrategyRunId(run.getId()),requests.findByStrategyRunId(run.getId())))return "Prior Batman exposure is not confirmed flat";
            if(day.equals(b.getClaimedAt().toLocalDate())){today++;if(Objects.equals(priorRunId,run.getId()) && "CLOSED".equals(b.getState())
                    && "DAY".equals(run.getInstance()) && "BATMAN_ENTRY_ABORT".equals(run.getExitReason())
                    && day.equals(run.getClosedAt().toLocalDate()) && Objects.equals(subscription,run.getSubscriptionId()))original=b;}
        }
        return today==1&&original!=null?null:"One manual retry is allowed only after today's original aborted basket; no additional retry is available";
    }
    public boolean claim(BatmanBasket basket){return claim(basket,null);}
    public boolean claim(BatmanBasket basket,String retryOfRunId){
        if(baskets.existsById(basket.getId()))return false;
        try{return Boolean.TRUE.equals(tx.execute(t->{
            users.lockForEnrollment(basket.getAppUserId()).orElseThrow(()->new IllegalArgumentException("Account not found"));
            var run=runs.findById(basket.getStrategyRunId()).orElse(null);
            String blocked=retryOfRunId==null?entryBlockReason(basket.getAppUserId(),basket.getBrokerCredentialsId(),basket.getSymbol(),basket.getClaimedAt().toLocalDate())
                    :run==null?"Missing retry run":retryBlockReason(basket.getAppUserId(),basket.getBrokerCredentialsId(),basket.getSymbol(),basket.getClaimedAt().toLocalDate(),retryOfRunId,run.getSubscriptionId());
            if(baskets.existsById(basket.getId())||blocked!=null)return false;
            em.persist(basket);em.flush();return true;
        }));}
        catch(RuntimeException e){if(baskets.existsById(basket.getId()))return false;throw e;}
    }
    /** Resume the same reviewed basket only after a terminal pre-broker trigger rejection and zero fills. */
    public BatmanBasket resumeNoOrder(Long user,Long broker,String symbol,Long subscription,LocalDate day,String originalRun){
        return tx.execute(t->{
            users.lockForEnrollment(user).orElseThrow();
            var history=baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(user,broker,symbol);
            BatmanBasket candidate=null;org.com.sharekhan.entity.StrategyRun retry=null;boolean original=false;int today=0;
            for(var b:history){
                var run=runs.findById(b.getStrategyRunId()).orElse(null);
                if(run==null||!Objects.equals(run.getAppUserId(),user)||!Objects.equals(run.getBrokerCredentialsId(),broker)||!Objects.equals(run.getSymbol(),symbol)
                        ||!Objects.equals(run.getSubscriptionId(),subscription)||!BatmanRules.TEMPLATE.equals(run.getTemplateId())
                        ||!BatmanReentryRules.confirmedFlat(run,trades.findByStrategyRunId(run.getId()),requests.findByStrategyRunId(run.getId())))return null;
                if(!day.equals(b.getClaimedAt().toLocalDate()))continue;today++;
                if(run.getId().equals(originalRun)&&"DAY".equals(run.getInstance())&&"BATMAN_ENTRY_ABORT".equals(run.getExitReason()))original=true;
                if("MANUAL_RETRY".equals(run.getInstance())){candidate=b;retry=run;}
            }
            if(today!=2||!original||candidate==null||!"CLOSED".equals(candidate.getState())||!"BATMAN_ENTRY_ABORT".equals(retry.getExitReason())||!trades.findByStrategyRunId(retry.getId()).isEmpty())return null;
            var attempted=requests.findByStrategyRunId(retry.getId());
            if(attempted.isEmpty()||attempted.stream().anyMatch(q->q.getStatus()!=org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED||!"ENTRY_NOT_READY_NO_ORDER".equals(q.getReason())
                    ||q.getComment()==null||!q.getComment().startsWith("Simulator waiting for traded LTP to reach")))return null;
            var plan=new org.json.JSONArray(candidate.getPlanJson());
            for(int i=0;i<plan.length();i++){var leg=plan.getJSONObject(i);if(!leg.optBoolean("marketEntry")||leg.optBoolean("noOrderResumeUsed"))return null;leg.put("noOrderResumeUsed",true);}
            em.persist(org.com.sharekhan.entity.TradeAuditEventEntity.builder().occurredAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))
                    .appUserId(user).strategyId(BatmanRules.TEMPLATE).strategyRunId(retry.getId()).symbol(symbol).eventType("BATMAN_NO_ORDER_RESUME").outcome("REVIEWED")
                    .reason("RESUME_SAME_APPROVED_MARKET_BASKET").details("No fills; terminal no-order requests retained; prior closedAt="+retry.getClosedAt()+"; prior exitReason="+retry.getExitReason()).build());
            retry.setStatus("OPEN");retry.setClosedAt(null);retry.setCloseRequestedAt(null);retry.setExitReason(null);retry.setMessage("Resuming reviewed market basket after terminal pre-broker trigger rejection");
            candidate.setState("ENTERING");candidate.setMessage("Same approved basket resumed; prior no-order rejection retained in audit");candidate.setPlanJson(plan.toString());em.flush();return candidate;
        });
    }
    public void arm(BatmanBasket basket,BigDecimal credit){tx.executeWithoutResult(t->{
        if(baskets.arm(basket.getId(),credit)==1&&runs.armBatmanRisk(basket.getStrategyRunId(),credit,org.com.sharekhan.strategy.run.StrategyRiskSettings.AmountMode.TOTAL)!=1)throw new IllegalStateException("Basket run is closing; risk arming cancelled");
    });}
}
