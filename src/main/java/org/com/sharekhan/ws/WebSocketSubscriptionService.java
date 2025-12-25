package org.com.sharekhan.ws;

import org.com.sharekhan.auth.TokenLoginAutomationService;

public interface WebSocketSubscriptionService {
    /**
     * Subscribe to LTP for a given scrip key.
     * @return true if an underlying WS subscribe was sent (transition 0->1),
     *         false if it was already subscribed and only a ref-count increment happened.
     */
    boolean subscribeToScrip(String scripCode);

    /**
     * Unsubscribe from LTP for a given scrip key when no more watchers remain.
     * @return true if an underlying WS unsubscribe was sent (transition 1->0),
     *         false if there are still watchers and only a ref-count decrement happened
     *         or if it wasn't subscribed.
     */
    boolean unsubscribeFromScrip(String scripCode);

    void subscribeToAck(String customerId);
}