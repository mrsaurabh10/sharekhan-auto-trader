package org.com.sharekhan.strategy.adxdmi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.StrategySubscriptionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component @RequiredArgsConstructor @Slf4j
public class AdxDmiScheduler {
    private final StrategySubscriptionRepository subscriptions;
    private final TriggeredTradeSetupRepository trades;
    private final StrategySubscriptionService subscriptionService;
    private final AdxDmiPaperBroker paper;
    private final org.com.sharekhan.service.TradeExecutionService execution;
    private final AdxDmiIndexExitPolicy indexExits;
    private final AdxDmiMarketData quotes;
    @Scheduled(fixedDelayString="${app.strategy.adx-dmi.poll-ms:2000}")
    public void poll(){poll(LocalDateTime.now(AdxDmiRules.IST),Clock.system(AdxDmiRules.IST));}
    void poll(LocalDateTime now){poll(now,Clock.fixed(now.atZone(AdxDmiRules.IST).toInstant(),AdxDmiRules.IST));}
    private void poll(LocalDateTime now,Clock clock){
        for(var pending:trades.findBySourceAndStatusIn(AdxDmiRules.PREMIUM_SOURCE,List.of(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION))){
            if(!AdxDmiEntryAdmission.validDecision(AdxDmiEntryAdmission.decision(pending),now) && !"ENTRY_USER_CANCEL_REQUESTED".equals(pending.getReason()))
                try{execution.cancelStrategyRunEntry(pending.getId(),pending.getStrategyRunId());}
                catch(RuntimeException failure){log.warn("ADX entry {} cancellation pending: {}",pending.getId(),failure.getMessage());}
        }
        // Versioned index policy observes fresh exact spot; placement/retries stay in the normal engine.
        // Continue even after noon or subscription pause, and retain exit intent through missing option books.
        for(var trade:trades.findBySourceAndStatusIn(AdxDmiRules.PREMIUM_SOURCE,List.of(
                org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED,org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_TRIGGERED,
                org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_FAILED))){
            if(!AdxDmiIndexExitRules.owns(trade))continue;
            try{
                Double spot=null;
                try{if(trade.getSpotScripCode()!=null){
                    var cash=AdxDmiRules.cashExchange(trade.getExchange());
                    var quote=quotes.spot(trade.getSpotScripCode(),cash);
                    if(quotes.freshSpot(cash,quote,Instant.now(),trade.getSpotScripCode()))spot=quote.getLastTradedPrice();
                }}catch(RuntimeException unavailable){log.debug("ADX index spot unavailable for {}: {}",trade.getId(),unavailable.getMessage());}
                var reason=indexExits.observe(trade.getId(),spot,LocalDateTime.now(clock));
                if(reason!=null)execution.squareOffStrategyRunTrade(trade.getId(),trade.getStrategyRunId(),reason);
            }catch(RuntimeException failure){log.warn("ADX index exit {} awaiting execution: {}",trade.getId(),failure.getMessage());}
        }
        // Existing positions remain managed even after a subscription is paused/cancelled.
        for(var t:trades.findBySourceAndStatusIn(AdxDmiRules.SOURCE,List.of(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED)))try{paper.evaluate(t.getId());}
        catch(RuntimeException failure){log.warn("ADX DMI paper exit {} awaiting data: {}",t.getId(),failure.getMessage());}
        if(!now.toLocalTime().isBefore(AdxDmiRules.SQUARE_OFF)){
            for(var trade:trades.findBySourceAndStatusIn(AdxDmiRules.PREMIUM_SOURCE,List.of(
                    org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED,org.com.sharekhan.enums.TriggeredTradeStatus.TARGET_ORDER_PLACED,
                    org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_ORDER_PLACED,org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_FAILED,org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_TRIGGERED))){
                try{
                    if(trade.getStatus()==org.com.sharekhan.enums.TriggeredTradeStatus.TARGET_ORDER_PLACED || trade.getStatus()==org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_ORDER_PLACED)execution.startIntradayExitChase(trade);
                    else execution.squareOffStrategyRunTrade(trade.getId(),trade.getStrategyRunId(),"INTRADAY_CLOSE");
                }catch(RuntimeException failure){log.warn("ADX intraday exit {} pending: {}",trade.getId(),failure.getMessage());}
            }
        }
        if(now.toLocalTime().isBefore(LocalTime.of(9,25)) || !now.toLocalTime().isBefore(AdxDmiRules.CUTOFF))return;
        for(var s:subscriptions.findByStatusInOrderByIdDesc(List.of("ACTIVE","TRIGGERED"))){
            if(!AdxDmiRules.TEMPLATE.equalsIgnoreCase(s.getTemplateId()))continue;
            try{
                subscriptionService.evaluate(s);
            }catch(RuntimeException failure){log.warn("ADX DMI subscription {}: {}",s.getId(),failure.getMessage());}
        }
    }
}
