package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;

public interface ModifiableEntryBrokerService extends BrokerService {

    OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade,
                                          BrokerContext context,
                                          String orderId,
                                          double newPrice);

    void cancelEntryOrder(TriggeredTradeSetupEntity trade,
                          BrokerContext context,
                          String orderId);
}
