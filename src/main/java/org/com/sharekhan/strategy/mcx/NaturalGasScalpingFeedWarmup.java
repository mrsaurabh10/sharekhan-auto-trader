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
public class NaturalGasScalpingFeedWarmup {
    private final StrategySubscriptionRepository subscriptions;
    private final ScriptMasterRepository scripts;
    private final NaturalGasLiveCandles live;
    private final TradeExecutionService execution;
    private final org.com.sharekhan.repository.BrokerCredentialsRepository brokers;
    private final NaturalGasShoonyaCandles shoonya;
    @Scheduled(cron="0 * 8-23 * * MON-FRI",zone="Asia/Kolkata")
    public void scheduled(){warm(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void warm(LocalDateTime now) {
        if(now.getDayOfWeek()==DayOfWeek.SATURDAY||now.getDayOfWeek()==DayOfWeek.SUNDAY
                ||now.toLocalTime().isBefore(LocalTime.of(8,55))||!now.toLocalTime().isBefore(NaturalGasSession.CLOSE))return;
        var active=subscriptions.findByStatusInOrderByIdDesc(List.of("ACTIVE","TRIGGERED")).stream()
                .filter(s->NaturalGasScalpingStrategy.TEMPLATE.equals(s.getTemplateId())).toList();
        if(active.isEmpty())return;
        try {
            var pair=NaturalGasContracts.resolve(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS"),now.toLocalDate());
            live.watch(pair.future().getScripCode());
            execution.warmUpSpotLtp(pair.future(),"MCX Natural Gas candle capture");
            // Minute-boundary prefetch normally completes before entry evaluation at :05.
            if(!now.toLocalTime().isBefore(LocalTime.of(9,5))
                && active.stream().anyMatch(s->s.getBrokerCredentialsId()!=null && brokers.findById(s.getBrokerCredentialsId())
                    .map(b->"SIMULATOR".equalsIgnoreCase(b.getBrokerName())).orElse(false)))
                shoonya.completed(pair.future(),now);
                shoonya.completed(pair.future(),now,3);
        }catch(RuntimeException e){log.warn("MCX Natural Gas candle feed not ready ({})",e.getClass().getSimpleName());}
    }
}
