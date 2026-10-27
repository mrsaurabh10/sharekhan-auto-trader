package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.StrategyRun;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Stop the Simulator day using loss-side P&L plus reserved costs; winning legs do not add risk allowance. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasScalpRiskMonitor {
    private final NaturalGasScalpPolicy policy;
    private final StrategyRunRepository runs;
    private final BrokerCredentialsRepository brokers;
    private final TriggeredTradeSetupRepository trades;
    private final TradeExecutionService execution;
    @Scheduled(fixedDelayString="${app.strategy.run-monitor-ms:1000}")
    public void scheduled(){monitor(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void monitor(LocalDateTime now) {
        if(!policy.enabled()||!NaturalGasSession.marketOpen(now))return;
        for(var run:runs.findByStatusIn(List.of("OPEN"))) {
            if(!NaturalGasScalpingStrategy.TEMPLATE.equals(run.getTemplateId())||!now.toLocalDate().equals(run.getTradingDay())
                    ||!brokers.findById(run.getBrokerCredentialsId()).filter(b->policy.forBroker(b.getBrokerName())&&Objects.equals(b.getAppUserId(),run.getAppUserId())).isPresent())continue;
            try {inspect(run,now);}catch(RuntimeException unavailable){log.warn("MCX_SCALP_RISK_WAIT | run={} type={}",run.getId(),unavailable.getClass().getSimpleName());}
        }
    }
    private void inspect(StrategyRun run,LocalDateTime now) {
        double used=0;boolean any=false;
        for(var trade:trades.findBySource(NaturalGasScalpingStrategy.SOURCE)) {
            if(!Objects.equals(run.getAppUserId(),trade.getAppUserId())||!Objects.equals(run.getBrokerCredentialsId(),trade.getBrokerCredentialsId())
                    ||trade.getEntryAt()==null||!trade.getEntryAt().toLocalDate().equals(now.toLocalDate()))continue;
            var status=trade.getStatus();boolean closed=status==TriggeredTradeStatus.EXITED_SUCCESS||status==TriggeredTradeStatus.COMPLETED;
            double price;
            if(closed) {
                if(trade.getExitPrice()==null)throw new IllegalArgumentException("Closed price unavailable");price=trade.getExitPrice();
            } else {
                if(status==null || !Set.of(TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_FAILED).contains(status))return;
                var quote=execution.strategyProfitExitQuote(trade,now.atZone(ZoneId.of("Asia/Kolkata")).toInstant());
                if(quote.isEmpty())return;price=quote.get().getBestBid();
            }
            used+=NaturalGasScalpDay.lossUsed(trade,price);any=true;
        }
        BigDecimal limit=NaturalGasScalpPolicy.DAILY_LIMIT;
        if(run.getRiskSettings()!=null&&run.getRiskSettings().getMaxLoss()!=null)limit=limit.min(run.getRiskSettings().scale(run.getRiskSettings().getMaxLoss(),run.getLots()));
        if(any&&BigDecimal.valueOf(used).compareTo(limit)>=0)runs.requestClose(run.getId(),"MCX_SCALP_DAILY_LOSS",now);
    }
}
