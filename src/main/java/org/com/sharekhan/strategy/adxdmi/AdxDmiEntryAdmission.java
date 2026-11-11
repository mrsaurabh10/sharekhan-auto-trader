package org.com.sharekhan.strategy.adxdmi;

import jakarta.persistence.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Admission/idempotency only. Placement and lifecycle stay in the normal execution service. */
@Service
public class AdxDmiEntryAdmission {
    private final EntityManager em;
    private final TriggerTradeRequestRepository requests;
    private final TriggeredTradeSetupRepository trades;
    private final StrategyRunRepository runs;
    private final StrategySubscriptionRepository subscriptions;
    private final TransactionTemplate tx;
    private final Clock clock;
    public record Admission(TriggerTradeRequestEntity request,boolean created) { }
    @org.springframework.beans.factory.annotation.Autowired
    public AdxDmiEntryAdmission(EntityManager em,TriggerTradeRequestRepository requests,TriggeredTradeSetupRepository trades,
            StrategyRunRepository runs,StrategySubscriptionRepository subscriptions,PlatformTransactionManager manager){
        this(em,requests,trades,runs,subscriptions,manager,Clock.system(AdxDmiRules.IST));
    }
    AdxDmiEntryAdmission(EntityManager em,TriggerTradeRequestRepository requests,TriggeredTradeSetupRepository trades,
            StrategyRunRepository runs,StrategySubscriptionRepository subscriptions,PlatformTransactionManager manager,Clock clock){
        this.em=em;this.requests=requests;this.trades=trades;this.runs=runs;this.subscriptions=subscriptions;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public static boolean entryWindow(LocalDateTime now){return !now.toLocalTime().isBefore(LocalTime.of(9,25)) && now.toLocalTime().isBefore(AdxDmiRules.CUTOFF);}
    static String field(String text,String name){
        if(text==null)return "";
        for(String value:text.split(";"))if(value.startsWith(name+"="))return value.substring(name.length()+1);
        return "";
    }
    public static boolean validDecision(String metadata,LocalDateTime now){
        try{deadline(metadata,now);return true;}catch(IllegalStateException invalid){return false;}
    }
    static void deadline(String metadata,LocalDateTime now){
        try{
            var end=LocalDateTime.parse(field(metadata,"ADX_DEADLINE"));
            if(!entryWindow(now) || !end.toLocalDate().equals(now.toLocalDate()) || !now.isBefore(end) || now.isBefore(end.minusMinutes(5)))throw new IllegalStateException("ADX signal entry window expired");
            if(field(metadata,"ADX_SIGNAL").isBlank())throw new IllegalStateException("ADX signal identity missing");
        }catch(java.time.format.DateTimeParseException invalid){throw new IllegalStateException("ADX server-owned signal metadata required");}
    }
    public record Batch(List<TriggerTradeRequestEntity> requests,boolean created) { }
    public Batch prepareBatch(List<TriggerRequest> orders,java.util.function.Function<TriggerRequest,TriggerTradeRequestEntity> prepare){
        var saved=new ArrayList<TriggerTradeRequestEntity>();
        var admission=prepare(orders.getFirst(),()->{
            for(var order:orders){
                var request=prepare.apply(order);
                // Keep all three out of queued price-trigger execution while the batch commits.
                request.setStatus(TriggeredTradeStatus.TRIGGERED);saved.add(requests.save(request));
            }
            return saved.getFirst();
        });
        return admission.created()?new Batch(List.copyOf(saved),true):new Batch(List.of(admission.request()),false);
    }
    public Admission prepare(TriggerRequest order,Supplier<TriggerTradeRequestEntity> prepare){
        deadline(order.getStrategyDecision(),LocalDateTime.now(clock));
        return tx.execute(status->{
            var account=em.find(BrokerCredentialsEntity.class,order.getBrokerCredentialsId(),LockModeType.PESSIMISTIC_WRITE);
            if(account==null || !Boolean.TRUE.equals(account.getActive()) || !Objects.equals(account.getAppUserId(),order.getUserId()))throw new IllegalStateException("Account unavailable");
            String key=field(order.getStrategyDecision(),"ADX_SIGNAL");
            var terminal=Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED,TriggeredTradeStatus.CANCELLED,TriggeredTradeStatus.REJECTED);
            for(String source:List.of(AdxDmiRules.SOURCE,AdxDmiRules.PREMIUM_SOURCE)){
                for(var old:requests.findBySourceIgnoreCaseAndStatusIn(source,List.of(TriggeredTradeStatus.values()))){
                    if(!Objects.equals(old.getAppUserId(),order.getUserId()) || !Objects.equals(old.getBrokerCredentialsId(),order.getBrokerCredentialsId()) || !order.getInstrument().equalsIgnoreCase(old.getSymbol()))continue;
                    if(key.equals(field(old.getComment(),"ADX_SIGNAL")))return new Admission(old,false);
                    // Failure/unknown states must be reconciled, not retried as new entries.
                    if(!terminal.contains(old.getStatus()) && old.getStatus()!=TriggeredTradeStatus.EXECUTED)throw new IllegalStateException("Previous ADX request remains unresolved");
                    if(old.getStatus()==TriggeredTradeStatus.EXECUTED && trades.findBySource(source).stream().noneMatch(t->Objects.equals(t.getTriggerRequestId(),old.getId())))
                        throw new IllegalStateException("Executed ADX request has no reconciled position");
                }
                for(var old:trades.findBySource(source)){
                    if(Objects.equals(old.getAppUserId(),order.getUserId()) && Objects.equals(old.getBrokerCredentialsId(),order.getBrokerCredentialsId()) && order.getInstrument().equalsIgnoreCase(old.getSymbol())
                            && (!terminal.contains(old.getStatus()) || old.getExitedAt()!=null && !old.getExitedAt().isBefore(LocalDateTime.parse(field(order.getStrategyDecision(),"ADX_DEADLINE")).minusMinutes(5))))
                        throw new IllegalStateException("An ADX position is open or exited within this signal candle");
                }
            }
            // This callback only prepares/persists the standard request. No broker RPC occurs in this transaction.
            var saved=prepare.get();return new Admission(saved,true);
        });
    }
    public OrderPlacementResult place(TriggeredTradeSetupEntity trade,Supplier<OrderPlacementResult> place){
        return tx.execute(status->{
            deadline(trade.getComment(),LocalDateTime.now(clock));
            if(trade.getStrategyRunId()==null)throw new IllegalStateException("ADX strategy run required");
            var run=runs.findById(trade.getStrategyRunId()).orElseThrow();
            if(run.getSubscriptionId()!=null){
                var sub=subscriptions.lockForRunEntry(run.getSubscriptionId()).orElseThrow();
                if(!List.of("ACTIVE","TRIGGERED").contains(sub.getStatus()) || !Objects.equals(sub.getAppUserId(),trade.getAppUserId()) || !Objects.equals(sub.getBrokerCredentialsId(),trade.getBrokerCredentialsId()))throw new IllegalStateException("Strategy subscription paused or changed");
            }
            em.refresh(run,LockModeType.PESSIMISTIC_WRITE);
            if(!"OPEN".equals(run.getStatus()) || !Objects.equals(run.getAppUserId(),trade.getAppUserId()) || !Objects.equals(run.getBrokerCredentialsId(),trade.getBrokerCredentialsId()))throw new IllegalStateException("Strategy instance closing or changed");
            return place.get();
        });
    }
}
