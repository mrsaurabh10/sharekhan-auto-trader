package org.com.sharekhan.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;

/** Monitors request settings independently of templates, subscriptions, or execution broker. */
@Component @RequiredArgsConstructor @Slf4j
public class TradeTimeExitMonitor {
    private final TriggeredTradeSetupRepository trades;
    private final TradeTimeExitService timer;
    private final TradeTimeExitExecution execution;
    @Scheduled(fixedDelayString="${app.trading.time-exit.poll-ms:2000}")
    public void poll() {
        for(var trade:trades.findByExecutionSettings_TimeExitMinutesIsNotNullAndStatusIn(List.of(
                TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.TARGET_ORDER_PLACED,
                TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED))) {
            try {
                if(timer.observeFromCache(trade.getId()))
                    execution.submit(trade.getId());
            } catch(Exception e){log.warn("Time-exit monitoring failed for trade {}: {}",trade.getId(),e.getMessage());}
        }
    }
}
