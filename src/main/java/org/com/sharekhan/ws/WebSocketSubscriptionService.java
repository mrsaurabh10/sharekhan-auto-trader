package org.com.sharekhan.ws;

import java.util.Set;

public interface WebSocketSubscriptionService {
    /** Named live demand; repeated retention is idempotent and survives a reconnect. */
    default boolean retainLtpFeed(String owner,String key){return false;}
    default void releaseLtpFeed(String owner) { }
    default void restoreOwnedLtpFeeds() { }
    default java.util.Map<String,Object> ltpFeedStatus(String key){return java.util.Map.of();}

    /** Observer may retain only an already active route. Implementations without ownership support refuse. */
    default boolean retainObserverFeed(String owner,String key,boolean full) { return false; }
    default void releaseObserverFeed(String owner) { }
    default java.util.Set<String> getObserverOnlyScripKeys() { return java.util.Set.of(); }

    /**
     * Subscribe to LTP for a given scrip key.
     * @return true if an underlying WS subscribe was sent (transition 0->1),
     *         false if it was already subscribed and only a ref-count increment happened.
     */
    boolean subscribeToScrip(String scripCode);

    /** Sharekhan's documented order-book depth feed, independently reference counted. */
    boolean subscribeToScripDepth(String scripCode);
    boolean unsubscribeFromScripDepth(String scripCode);
    boolean hasDepthFeed(String scripCode);

    /**
     * Subscribe to LTP-only feed for a given scrip key.
     * Use this for spot/index references where full-depth feed may not publish.
     */
    boolean subscribeToScripLtp(String scripCode);

    /**
     * Unsubscribe from LTP for a given scrip key when no more watchers remain.
     * @return true if an underlying WS unsubscribe was sent (transition 1->0),
     *         false if there are still watchers and only a ref-count decrement happened
     *         or if it wasn't subscribed.
     */
    boolean unsubscribeFromScrip(String scripCode);

    /**
     * Unsubscribe from LTP-only feed for a given scrip key when no more watchers remain.
     */
    boolean unsubscribeFromScripLtp(String scripCode);

    void subscribeToAck(String customerId);

    /**
     * Get locally retained feed demand, including pending/disconnected routes.
     * This is not proof of a sent subscription or current ticks; use ltpFeedStatus for LTP health.
     */
    Set<String> getActiveScripKeys();
}
