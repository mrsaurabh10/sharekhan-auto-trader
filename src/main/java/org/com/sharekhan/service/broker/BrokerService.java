package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;

public interface BrokerService {
    Broker getBroker();

    /** Fresh, account-scoped spendable funds; empty means unavailable, never permission to submit. */
    default java.util.OptionalDouble availableOptionBuyingFunds(TriggeredTradeSetupEntity trade, BrokerContext context) {
        return java.util.OptionalDouble.empty();
    }

    
    OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp);
    
    OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice);
}
