package org.com.sharekhan.strategy.nifty;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.StrategyFeedChanged;
import org.com.sharekhan.strategy.StrategySupport;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.*;
import java.util.*;

/** Owns market-data demand only. Never evaluates a strategy or submits an order. */
@Component @RequiredArgsConstructor @Slf4j
public class NiftyThreeCandleFeedLifecycle {
    private final StrategySubscriptionRepository subscriptions;
    private final TriggeredTradeSetupRepository trades;
    private final StrategySupport support;
    private final ScriptMasterRepository scripts;
    private final WebSocketSubscriptionService feeds;
    private final Set<String> held=new HashSet<>();
    private static final Set<org.com.sharekhan.enums.TriggeredTradeStatus> TERMINAL=Set.of(
            org.com.sharekhan.enums.TriggeredTradeStatus.EXITED_SUCCESS,org.com.sharekhan.enums.TriggeredTradeStatus.COMPLETED,
            org.com.sharekhan.enums.TriggeredTradeStatus.CANCELLED,org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED);

    @EventListener(ApplicationReadyEvent.class)
    public void started(){refresh();}

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public void changed(StrategyFeedChanged event){if(NiftyThreeCandleRules.TEMPLATE.equalsIgnoreCase(event.templateId()))refresh();}

    public void refresh(){
        try {reconcile();}
        catch(RuntimeException failure){log.warn("Nifty feed ownership refresh awaiting data: {}",failure.getClass().getSimpleName());}
    }

    synchronized void reconcile(){
        var active=subscriptions.findByStatusInOrderByIdDesc(List.of("ACTIVE","TRIGGERED")).stream()
                .filter(s->NiftyThreeCandleRules.TEMPLATE.equalsIgnoreCase(s.getTemplateId())&&s.getId()!=null)
                .filter(s->List.of("ACTIVE","TRIGGERED").contains(s.getStatus())).toList();
        var positions=trades.findBySource(NiftyThreeCandleRules.SOURCE).stream()
                .filter(t->t.getId()!=null&&(t.getStatus()==null||!TERMINAL.contains(t.getStatus()))).toList();
        var desired=new LinkedHashMap<String,String>();
        if(!active.isEmpty()){
            var spot=support.resolveSpotScript("NIFTY");
            if(spot.getScripCode()==null||!"NC".equalsIgnoreCase(spot.getExchange()))throw new IllegalStateException("Exact Nifty spot identity is unavailable");
            for(var subscription:active)desired.put("NIFTY_3C_SUB:"+subscription.getId(),"NC"+spot.getScripCode());
        }
        for(var position:positions){
            if(position.getSpotScripCode()==null)throw new IllegalStateException("Open Nifty position is missing spot identity");
            var spot=scripts.findByScripCode(position.getSpotScripCode());
            if(spot==null||!"NC".equalsIgnoreCase(spot.getExchange()))throw new IllegalStateException("Open Nifty position spot identity is unavailable");
            desired.put("NIFTY_3C_TRADE:"+position.getId(),"NC"+position.getSpotScripCode());
        }
        // Acquire first: pausing a subscription must not interrupt its open position's feed.
        for(var entry:desired.entrySet()){
            if(!feeds.retainLtpFeed(entry.getKey(),entry.getValue()))throw new IllegalStateException("Unable to retain Nifty feed demand");
            held.add(entry.getKey());
        }
        for(String owner:new HashSet<>(held))if(!desired.containsKey(owner)){feeds.releaseLtpFeed(owner);held.remove(owner);}
    }
}
