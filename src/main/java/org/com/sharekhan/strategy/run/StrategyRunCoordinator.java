package org.com.sharekhan.strategy.run;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.StrategyRun;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.StrategySupport;
import org.com.sharekhan.strategy.straddle.ExpiryStraddleRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Server-owned scope; clients cannot choose run membership. No broker dependencies. */
@Service
public class StrategyRunCoordinator {
    private final StrategyRunRepository runs;
    private final Clock clock;
    private final StrategySubscriptionRepository subscriptions;
    private final EntityManager em;
    private final TransactionTemplate insert;
    private final ThreadLocal<StrategyRun> context=new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired
    public StrategyRunCoordinator(StrategyRunRepository runs,StrategySubscriptionRepository subscriptions,EntityManager em,PlatformTransactionManager manager) {
        this(runs,subscriptions,em,manager,Clock.system(StrategySupport.MARKET_ZONE));
    }
    StrategyRunCoordinator(StrategyRunRepository runs,StrategySubscriptionRepository subscriptions,EntityManager em,PlatformTransactionManager manager,Clock clock) {
        this.clock=clock;this.runs=runs;this.subscriptions=subscriptions;this.em=em;
        insert=new TransactionTemplate(manager);insert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public StrategyApplyResponse evaluate(StrategyApplyRequest request,Supplier<StrategyApplyResponse> action) {
        if(request.getUserId()==null || request.getBrokerCredentialsId()==null) throw new IllegalArgumentException("Select an account and broker for strategy run tracking");
        if(context.get()!=null) throw new IllegalStateException("Nested strategy evaluation is not supported");
        var now=LocalDateTime.now(clock);
        String instance="DAY";
        if(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE.equalsIgnoreCase(request.getTemplateId())) {
            var baskets=runs.findByAppUserIdAndBrokerCredentialsIdAndTemplateIdAndTradingDayOrderByCreatedAtAsc(
                    request.getUserId(),request.getBrokerCredentialsId(),org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE,now.toLocalDate()).stream()
                    .filter(r->Objects.equals(r.getSubscriptionId(),request.getSubscriptionId()) && Objects.equals(r.getSymbol(),request.getSymbol())).toList();
            instance=!baskets.isEmpty() && !"CLOSED".equals(baskets.getLast().getStatus())?baskets.getLast().getInstance():"BASKET"+(baskets.size()+1);
        }
        if(ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId())) {
            var slot=ExpiryStraddleRules.SESSIONS.stream().filter(s->s.entryWindow(now.toLocalTime())).findFirst();
            if(slot.isEmpty()) return action.get(); // No new instance for a missed entry minute.
            instance=slot.get().id();
        }
        if(request.getSubscriptionId()!=null) {
            var sub=subscriptions.findById(request.getSubscriptionId()).orElseThrow();
            if(!Objects.equals(sub.getAppUserId(),request.getUserId()) || !Objects.equals(sub.getBrokerCredentialsId(),request.getBrokerCredentialsId())
                    || !sub.getTemplateId().equalsIgnoreCase(request.getTemplateId())) throw new IllegalArgumentException("Strategy subscription scope mismatch");
        }
        String identity=Objects.toString(request.getSubscriptionId(),"direct:"+request.getSymbol())+":"+request.getUserId()+":"+request.getBrokerCredentialsId()+":"+request.getTemplateId().toUpperCase(Locale.ROOT)+":"+now.toLocalDate()+":"+instance;
        String id;
        try {id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
        var existing=runs.findById(id);
        if(existing.isPresent() && !"OPEN".equals(existing.get().getStatus())) return StrategyApplyResponse.builder().status("waiting")
                .templateId(request.getTemplateId()).symbol(request.getSymbol()).message("This strategy instance is closing/closed; future instances remain scheduled").build();
        var rules=StrategyRiskSettings.validate(request.getRiskSettings(),request.getLots());
        if(rules==null && ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId()) && request.getProfitTargetPerLot()!=null) {
            rules=new StrategyRiskSettings();rules.setAmountMode(StrategyRiskSettings.AmountMode.PER_LOT);rules.setProfitTarget(request.getProfitTargetPerLot());
            rules=StrategyRiskSettings.validate(rules,request.getLots());
        }
        context.set(existing.orElse(StrategyRun.builder().id(id).appUserId(request.getUserId()).brokerCredentialsId(request.getBrokerCredentialsId())
                .subscriptionId(request.getSubscriptionId()).templateId(request.getTemplateId()).symbol(request.getSymbol()).tradingDay(now.toLocalDate()).instance(instance)
                .lots(request.getLots()).riskSettings(rules).status("OPEN").createdAt(now).message("Strategy instance active").build()));
        try {return action.get();} finally {context.remove();}
    }
    public String ensureCurrentRun() {
        var run=context.get();if(run==null) return null;
        try {
            insert.executeWithoutResult(tx->{
                // Serialize instance creation with subscription cancellation. Close-all's subsequent query
                // therefore sees every run created before the pause, even an in-flight evaluation.
                if(run.getSubscriptionId()!=null && subscriptions.lockForRunEntry(run.getSubscriptionId())
                        .map(s->!List.of("ACTIVE","TRIGGERED").contains(s.getStatus())).orElse(true))
                    throw new IllegalStateException("Strategy subscription paused; no new run entries allowed");
                if(!runs.existsById(run.getId())) {em.persist(run);em.flush();}
            });
        } catch(jakarta.persistence.EntityExistsException | org.hibernate.exception.ConstraintViolationException | org.springframework.dao.DataIntegrityViolationException conflict) {if(!runs.existsById(run.getId())) throw conflict;}
        requireOpen(run.getId());return run.getId();
    }
    public String currentId() {
        var run=context.get();if(run==null)throw new IllegalStateException("A strategy run context is required for an averaging basket");
        return run.getId();
    }
    public void attach(TriggerRequest request) {
        var run=context.get();if(run==null) {request.setStrategyRunId(null);return;}
        if(!Objects.equals(request.getUserId(),run.getAppUserId()) || !Objects.equals(request.getBrokerCredentialsId(),run.getBrokerCredentialsId())) throw new IllegalArgumentException("Entry owner/broker differs from strategy instance");
        request.setStrategyRunId(ensureCurrentRun());
    }
    public boolean isOpen(String id) {return id==null || runs.findById(id).map(r->"OPEN".equals(r.getStatus())).orElse(false);}
    public void requireOpen(String id) {if(!isOpen(id)) throw new IllegalStateException("Strategy instance is closing or closed; new entries blocked");}
    public void requestClose(String id,String reason) {runs.requestClose(id,reason,LocalDateTime.now(clock));}
}
