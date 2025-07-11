package org.com.sharekhan.ws;

import org.com.sharekhan.auth.TokenLoginAutomationService;

public interface WebSocketSubscriptionService {
    void unsubscribeFromScrip(String scripCode);
    void subscribeToScrip(String scripCode);
    void subscribeToAck(String customerId);
}