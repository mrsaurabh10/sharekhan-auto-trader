package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;

public interface OrderStatusBrokerService {

    JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId);

    /** Today's broker order book, including Sharekhan-managed BTP child legs. */
    default JSONObject fetchDayOrders(BrokerContext context) { return null; }
}
