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
        
        Double pnl = null;
        Double entryPriceForPnl = resolveEntryPriceForPnl(trade);
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

    private Double resolveEntryPriceForPnl(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getActualEntryPrice() != null) {
            return trade.getActualEntryPrice();
        }
        if (usesSpotReference(trade)) {
            log.warn("[SIMULATOR] Cannot compute PnL for spot-referenced trade {} because actualEntryPrice is missing. entryPrice={} is a reference price.",
                    trade.getId(), trade.getEntryPrice());
            return null;
        }
        return trade.getEntryPrice();
    }

    private boolean usesSpotReference(TriggeredTradeSetupEntity trade) {
        return Boolean.TRUE.equals(trade.getUseSpotForEntry())
                || Boolean.TRUE.equals(trade.getUseSpotForSl())
                || Boolean.TRUE.equals(trade.getUseSpotForTarget())
                || Boolean.TRUE.equals(trade.getUseSpotPrice());
    }
}
