package org.com.sharekhan.entity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.com.sharekhan.service.TradeCostCalculator;

public class TradeCostPersistenceListener {

    @PrePersist
    @PreUpdate
    public void calculateNetPnl(TriggeredTradeSetupEntity trade) {
        TradeCostCalculator.TradeCharges charges = TradeCostCalculator.calculateCharges(trade);
        if (charges != null) {
            trade.setTradeCost(charges.totalTradeCost());
            trade.setEffectivePnl(charges.effectivePnl());
        }
    }
}
