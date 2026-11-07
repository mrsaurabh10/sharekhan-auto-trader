package org.com.sharekhan.strategy.nifty;

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
public class NiftyThreeCandleScheduler {
    private final StrategySubscriptionRepository subscriptions;
    private final TriggeredTradeSetupRepository trades;
    private final StrategySubscriptionService subscriptionService;
    private final NiftyThreeCandlePaperBroker paper;
    @Scheduled(fixedDelayString="${app.strategy.nifty-three-candle.poll-ms:2000}")
    public void poll(){
        // Existing positions remain managed even after a subscription is paused/cancelled.
        for(var t:trades.findBySourceAndStatusIn(NiftyThreeCandleRules.SOURCE,List.of(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED)))try{paper.evaluate(t.getId());}
        catch(RuntimeException failure){log.warn("Nifty paper exit {} awaiting data: {}",t.getId(),failure.getMessage());}
        var now=LocalDateTime.now(NiftyThreeCandleRules.IST);
        if(now.toLocalTime().isBefore(LocalTime.of(9,30)) || !now.toLocalTime().isBefore(LocalTime.of(15,30)))return;
        for(var s:subscriptions.findByStatusInOrderByIdDesc(List.of("ACTIVE","TRIGGERED"))){
            if(!NiftyThreeCandleRules.TEMPLATE.equalsIgnoreCase(s.getTemplateId()))continue;
            try{
                subscriptionService.evaluate(s);
            }catch(RuntimeException failure){log.warn("Nifty paper subscription {}: {}",s.getId(),failure.getMessage());}
        }
    }
}
