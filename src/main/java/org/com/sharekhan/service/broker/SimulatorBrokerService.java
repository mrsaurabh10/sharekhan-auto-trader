package org.com.sharekhan.service.broker;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SimulatorBrokerService implements BrokerService {

    @Override
    public Broker getBroker() {
        return Broker.SIMULATOR;
    }

    @Override
    public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp) {
        log.info("🚀 [SIMULATOR] Placing BUY order for {} at {}", trade.getSymbol(), ltp);
        String orderId = "SIM-" + System.currentTimeMillis();
        
        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status("Fully Executed")
                .attemptedPrice(ltp)
                .executedPrice(ltp)
                .build();
    }

    @Override
    public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice) {
        log.info("🚀 [SIMULATOR] Placing SELL/EXIT order for {} at {}", trade.getSymbol(), exitPrice);
        String orderId = "SIM-EXIT-" + System.currentTimeMillis();
        
        double pnl = 0.0;
        Double entryPriceForPnl = trade.getActualEntryPrice() != null ? trade.getActualEntryPrice() : trade.getEntryPrice();
        if (entryPriceForPnl != null && trade.getQuantity() != null) {
            pnl = (exitPrice - entryPriceForPnl) * trade.getQuantity();
        }

        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status("Fully Executed")
                .attemptedPrice(exitPrice)
                .executedPrice(exitPrice)
                .pnl(pnl)
                .build();
    }
}
