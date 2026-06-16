package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;

public interface OrderStatusBrokerService {

    JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId);
}
