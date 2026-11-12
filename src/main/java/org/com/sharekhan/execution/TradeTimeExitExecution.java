package org.com.sharekhan.execution;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.springframework.stereotype.Component;

/** Use the shared deduplicating execution queues; broker RPC must not block price/timer threads. */
@Component @RequiredArgsConstructor
public class TradeTimeExitExecution {
    private final TriggeredTradeSetupRepository trades;
    private final BrokerCredentialsRepository brokers;
    private final OrderExecutionDispatcher dispatcher;
    private final TradeExecutionService execution;
    public boolean submit(Long id) {
        var trade=trades.findById(id).orElse(null);
        if(!TradeTimeExitService.configured(trade) || !TradeTimeExitService.REASON.equals(trade.getExitReason()))return false;
        String key="EXIT:"+id;
        if(trade.getBrokerCredentialsId()!=null && brokers.findById(trade.getBrokerCredentialsId())
                .filter(b->"Simulator".equalsIgnoreCase(b.getBrokerName())).isPresent())key="SIM:"+key;
        return dispatcher.submit(key,()->execution.squareOffTimeExitTrade(id));
    }
}
