package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;

public interface OrderUpdateBrokerService extends OrderStatusBrokerService {
    /** Validate ownership/order identity and translate a stream row into the normal reconciliation contract. */
    JSONObject normalizeOrderUpdate(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId, JSONObject row);
}
