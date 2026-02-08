package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;

public interface BrokerService {
    Broker getBroker();
    
    OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp);
    
    OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice);
}
