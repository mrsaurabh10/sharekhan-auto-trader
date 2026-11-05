package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Latch exits durably in the run; normal run reconciliation closes the OPTION at its own bid. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasPineMonitor {
    private final StrategyRunRepository runs;
    private final TriggeredTradeSetupRepository trades;
    private final ScriptMasterRepository scripts;
    private final BrokerCredentialsRepository brokers;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    private final NaturalGasDataPolicy dataPolicy;
    private final NaturalGasPinePolicy policy;
    @Scheduled(fixedDelayString="${app.strategy.ng-pine.monitor-ms:1000}")
    public void scheduled(){monitor(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void monitor(LocalDateTime now) {
        if(!NaturalGasSession.marketOpen(now))return;
        for(var run:runs.findByTemplateIdAndStatusIn(NaturalGasPineStrategy.TEMPLATE,List.of("OPEN"))) {
            try {inspect(run,now);}catch(RuntimeException failure) {
                runs.recordMessage(run.getId(),"NG Pine exit data unavailable; no fresh-price conclusion");
                log.warn("NG_PINE_EXIT_WAIT | run={} type={}",run.getId(),failure.getClass().getSimpleName());
            }
        }
    }
    private void inspect(StrategyRun run,LocalDateTime now) {
        var broker=brokers.findById(run.getBrokerCredentialsId()).filter(b->Objects.equals(b.getAppUserId(),run.getAppUserId())
                && Set.of("SIMULATOR","SHAREKHAN").contains(Objects.toString(b.getBrokerName(),"").toUpperCase(Locale.ROOT))).orElse(null);
        if(broker==null)return;
        if(run.getTradingDay().isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(policy.getSquareOff())) {
            runs.requestClose(run.getId(),"MCX_PINE_SESSION_CLOSE",now);return;
        }
        if(policy.blocked(now)){runs.requestClose(run.getId(),"MCX_PINE_NEWS_BLACKOUT",now);return;}
        var members=trades.findByStrategyRunId(run.getId());
        if(run.getCreatedAt()!=null && now.isAfter(run.getCreatedAt().plusSeconds(75))
                && members.stream().noneMatch(t->t.getActualEntryPrice()!=null)) {
            runs.requestClose(run.getId(),"MCX_PINE_ENTRY_EXPIRED",now);return;
        }
        for(var t:members) {
            if(!Objects.equals(t.getAppUserId(),run.getAppUserId()) || !Objects.equals(t.getBrokerCredentialsId(),run.getBrokerCredentialsId())
                    || !NaturalGasPineStrategy.owns(t.getSource()))throw new IllegalStateException("NG Pine member ownership mismatch");
            if(NaturalGasPineStrategy.closed(t) || t.getActualEntryPrice()==null)continue;
            var instant=now.atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            var q=t.getSpotScripCode()==null?null:quotes.getSnapshot(t.getSpotScripCode()).orElse(null);
            if(!NaturalGasPineStrategy.freshFuture(q,instant,t.getSpotScripCode()))continue;
            String reason=priceExit(t,q.getLastTradedPrice());
            if(reason!=null){runs.requestClose(run.getId(),reason,now);return;}
            if(policy.isExitOnInvalidation()) {
                var future=scripts.findByScripCode(t.getSpotScripCode());
                if(future==null)continue;
                var candles=dataPolicy.sourceForBroker(broker.getBrokerName())==NaturalGasCandleSource.SHOONYA_TPS
                        ?data.shoonyaCandles(future,now):data.candles(future.getScripCode(),now);
                var snapshot=NaturalGasPineSignals.inspect(candles,now,policy);
                // Never invalidate using a bar that predates the confirmed option fill.
                if(snapshot.fresh(now) && t.getEntryAt()!=null && snapshot.end().isAfter(t.getEntryAt()) && snapshot.invalidated(t.getOptionType())) {
                    runs.requestClose(run.getId(),"MCX_PINE_TREND_INVALIDATED",now);return;
                }
            }
        }
    }
    static String priceExit(TriggeredTradeSetupEntity t,double futuresPrice) {
        boolean ce="CE".equals(t.getOptionType());
        if(!ce && !"PE".equals(t.getOptionType()))return null;
        if(t.getStopLoss()!=null && (ce?futuresPrice<=t.getStopLoss():futuresPrice>=t.getStopLoss()))return "MCX_PINE_FUTURES_STOP";
        if(t.getTarget1()!=null && (ce?futuresPrice>=t.getTarget1():futuresPrice<=t.getTarget1()))return "MCX_PINE_FUTURES_TARGET";
        return null;
    }
}
