package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.StrategyRun;
import org.com.sharekhan.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Basket exits run independently of entry freshness and even when a subscription is paused. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasAveragingMonitor {
    private final StrategyRunRepository runs;
    private final BrokerCredentialsRepository brokers;
    private final TriggeredTradeSetupRepository trades;
    private final ScriptMasterRepository scripts;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    @Scheduled(fixedDelayString="${app.strategy.ng-averaging.monitor-ms:1000}")
    public void scheduled(){monitor(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void monitor(LocalDateTime now) {
        if(!NaturalGasSession.marketOpen(now))return;
        for(var run:runs.findByTemplateIdAndStatusIn(NaturalGasAveragingStrategy.TEMPLATE,List.of("OPEN"))) {
            if(!now.toLocalDate().equals(run.getTradingDay()))continue; // Common run service closes overnight/session exposure.
            if(!brokers.findById(run.getBrokerCredentialsId()).filter(b->Objects.equals(b.getAppUserId(),run.getAppUserId()) && "SIMULATOR".equalsIgnoreCase(b.getBrokerName())).isPresent())continue;
            try {inspect(run,now);}catch(RuntimeException unavailable) {
                runs.recordMessage(run.getId(),"Averaging risk/trend data unavailable; additions must wait");
                log.warn("MCX_AVERAGING_RISK_WAIT | run={} type={}",run.getId(),unavailable.getClass().getSimpleName());
            }
        }
    }
    private void inspect(StrategyRun run,LocalDateTime now) {
        var owned=trades.findBySource(NaturalGasAveragingStrategy.SOURCE).stream()
                .filter(t->Objects.equals(t.getAppUserId(),run.getAppUserId()) && Objects.equals(t.getBrokerCredentialsId(),run.getBrokerCredentialsId())).toList();
        var held=owned.stream().filter(t->run.getId().equals(t.getStrategyRunId()) && t.getActualEntryPrice()!=null).findFirst().orElse(null);
        if(held==null)return;
        var state=NaturalGasAveragingBook.inspect(owned,run.getAppUserId(),run.getBrokerCredentialsId(),now,
                NaturalGasAveragingStrategy.marks(owned,quotes,now.atZone(ZoneId.of("Asia/Kolkata")).toInstant()),NaturalGasAveragingBook.OPTION_TICK);
        if(state.problem()!=null){runs.requestClose(run.getId(),"MCX_AVERAGING_RECONCILIATION",now);return;}
        var basket=state.current(run.getId());if(basket==null)return;
        if(basket.allClosed()){runs.requestClose(run.getId(),"MCX_AVERAGING_BASKET_FLAT",now);return;}
        if(basket.marked()) {
            if(state.dailyUsed()>=NaturalGasAveragingBook.DAILY_LOSS || bidAtOrBelowFloor(basket)) {
                runs.requestClose(run.getId(),"MCX_AVERAGING_DAILY_LOSS",now);return;
            }
            if(basket.net()>=NaturalGasAveragingBook.TARGET){runs.requestClose(run.getId(),"MCX_AVERAGING_BASKET_TARGET",now);return;}
        }
        // Trend reversal can request exit even when the option book is unavailable.
        var options=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS");
        var pair=NaturalGasContracts.resolve(options,now.toLocalDate());
        var signal=NaturalGasAveragingSignals.inspect(data.shoonyaCandles(pair.future(),now),now);
        if(signal.reversed(held.getOptionType())){runs.requestClose(run.getId(),"MCX_AVERAGING_TREND_REVERSED",now);return;}
        runs.recordMessage(run.getId(),"Averaging basket "+basket.lots()+"/4 lots; paper net="+(basket.marked()?money(basket.net()):"unavailable")
                +"; target=1250; daily loss used="+(Double.isFinite(state.dailyUsed())?money(state.dailyUsed()):"unavailable")
                +"/3000; premium stop="+money(basket.stopFloor())+"; reserved costs="+money(basket.costs())
                +(!signal.trendReady()?"; trend data unavailable; additions paused":""));
    }
    private boolean bidAtOrBelowFloor(NaturalGasAveragingBook.Basket basket) {
        double openCost=0,realized=0;long quantity=0;
        for(var t:basket.legs()) {
            if(NaturalGasAveragingBook.closed(t))realized+=(t.getExitPrice()-t.getActualEntryPrice())*t.getQuantity();
            else {openCost+=t.getActualEntryPrice()*t.getQuantity();quantity+=t.getQuantity();}
        }
        return quantity>0 && (basket.gross()-realized+openCost)/quantity<=basket.stopFloor()+1e-8;
    }

    private static String money(double x){return String.format(java.util.Locale.ROOT,"%.2f",x);}
}
