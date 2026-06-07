package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;

public interface TriggerPriceEntryBrokerService extends BrokerService {

    OrderPlacementResult placeTriggerPriceEntryOrder(TriggeredTradeSetupEntity trade,
                                                     BrokerContext context,
                                                     double entryPrice);
}
