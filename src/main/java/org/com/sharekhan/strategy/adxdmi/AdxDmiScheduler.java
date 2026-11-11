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
    @Scheduled(fixedDelayString="${app.strategy.adx-dmi.poll-ms:2000}")
    public void poll(){poll(LocalDateTime.now(AdxDmiRules.IST));}
    void poll(LocalDateTime now){
        for(var pending:trades.findBySourceAndStatusIn(AdxDmiRules.PREMIUM_SOURCE,List.of(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION))){
            if(!AdxDmiEntryAdmission.validDecision(pending.getComment(),now) && !"ENTRY_USER_CANCEL_REQUESTED".equals(pending.getReason()))
                try{execution.cancelStrategyRunEntry(pending.getId(),pending.getStrategyRunId());}
                catch(RuntimeException failure){log.warn("ADX entry {} cancellation pending: {}",pending.getId(),failure.getMessage());}
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
