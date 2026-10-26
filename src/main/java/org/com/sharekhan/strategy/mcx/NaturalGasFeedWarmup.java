package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Retain the exact futures feed before 09:00 even though entry evaluation has not opened. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasFeedWarmup {
    private final StrategySubscriptionRepository subscriptions;
    private final ScriptMasterRepository scripts;
    private final NaturalGasLiveCandles live;
    private final TradeExecutionService execution;
    @Scheduled(cron="0 * 8-23 * * MON-FRI",zone="Asia/Kolkata")
    public void scheduled(){warm(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void warm(LocalDateTime now) {
        if(now.getDayOfWeek()==DayOfWeek.SATURDAY||now.getDayOfWeek()==DayOfWeek.SUNDAY
                ||now.toLocalTime().isBefore(LocalTime.of(8,55))||!now.toLocalTime().isBefore(NaturalGasSession.CLOSE))return;
        boolean active=subscriptions.findByStatusInOrderByIdDesc(List.of("ACTIVE","TRIGGERED")).stream()
                .anyMatch(s->NaturalGasSession.TEMPLATE.equals(s.getTemplateId()));
        if(!active)return;
        try {
            var pair=NaturalGasContracts.resolve(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS"),now.toLocalDate());
            live.watch(pair.future().getScripCode());
            execution.warmUpSpotLtp(pair.future(),"MCX Natural Gas candle capture");
        }catch(RuntimeException e){log.warn("MCX Natural Gas candle feed not ready ({})",e.getClass().getSimpleName());}
    }
}
