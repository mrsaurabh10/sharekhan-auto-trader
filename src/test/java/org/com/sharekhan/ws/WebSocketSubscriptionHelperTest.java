package org.com.sharekhan.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketSubscriptionHelperTest {

    private TestConnector connector;
    private WebSocketSubscriptionHelper helper;

    @BeforeEach
    void setUp() {
        connector = new TestConnector();
        helper = new WebSocketSubscriptionHelper(connector);
    }

    @Test void explicitDepthFeedUsesDocumentedModeAndSeparateReferences(){
        helper.subscribeToScripDepth("NF64159");helper.subscribeToScripDepth("NF64159");
        assertTrue(helper.hasDepthFeed("NF64159"));
        assertEquals(1,connector.lastMessages.size());
        assertEquals("{\"action\":\"feed\",\"key\":[\"depth\"],\"value\":[\"NF64159\"]}",connector.lastMessages.get(0));
        helper.subscribeToScrip("NF64159");assertFalse(helper.unsubscribeFromScripDepth("NF64159"));
        assertTrue(helper.unsubscribeFromScripDepth("NF64159"));assertFalse(helper.hasDepthFeed("NF64159"));
        assertTrue(helper.getActiveScripKeys().contains("NF64159"));
    }
    @Test
    void firstDepthSubscriptionSendsFullFeed() {
        boolean newSub = helper.subscribeToScrip("NC22");

        assertTrue(newSub, "First subscription should return true");
        assertEquals(1, connector.lastMessages.size());
        assertEquals("{\"action\":\"feed\",\"key\":[\"full\"],\"value\":[\"NC22\"]}",
                connector.lastMessages.get(0));
    }

    @Test
    void ltpSubscriptionSendsLtpFeed() {
        boolean newSub = helper.subscribeToScripLtp("NC20000");

        assertTrue(newSub, "First LTP subscription should return true");
        assertEquals(1, connector.lastMessages.size());
        assertEquals("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"NC20000\"]}",
                connector.lastMessages.get(0));
        assertTrue(helper.getActiveScripKeys().contains("NC20000"));
    }

    @Test
    void fullAndLtpFeedsAreReferenceCountedSeparately() {
        helper.subscribeToScrip("NC20000");
        helper.subscribeToScripLtp("NC20000");
        connector.lastMessages.clear();

        boolean fullUnsub = helper.unsubscribeFromScrip("NC20000");

        assertTrue(fullUnsub, "Full feed should unsubscribe independently");
        assertEquals("{\"action\":\"unsubscribe\",\"key\":[\"full\"],\"value\":[\"NC20000\"]}",
                connector.lastMessages.get(0));
        assertTrue(helper.getActiveScripKeys().contains("NC20000"));
    }

    @Test
    void subsequentSubscriptionsDoNotResend() {
        helper.subscribeToScrip("NC22");
        connector.lastMessages.clear();

        boolean newSub = helper.subscribeToScrip("NC22");

        assertFalse(newSub, "Second subscription should be a no-op");
        assertTrue(connector.lastMessages.isEmpty(), "No additional message should be sent");
    }

    @Test
    void unsubscribeWhenReferenceFallsToZero() {
        helper.subscribeToScrip("NC22");
        connector.lastMessages.clear();

        boolean unsub = helper.unsubscribeFromScrip("NC22");

        assertTrue(unsub, "Should send unsubscribe when ref count hits zero");
        assertEquals(1, connector.lastMessages.size());
        assertEquals("{\"action\":\"unsubscribe\",\"key\":[\"full\"],\"value\":[\"NC22\"]}",
                connector.lastMessages.get(0));
    }

    @Test
    void unsubscribeKeepsSubscriptionWhenRefCountRemains() {
        helper.subscribeToScrip("NC22");
        helper.subscribeToScrip("NC22");
        connector.lastMessages.clear();

        boolean unsub = helper.unsubscribeFromScrip("NC22");

        assertFalse(unsub, "Should not send unsubscribe when references remain");
        assertTrue(connector.lastMessages.isEmpty());
    }

    @Test void observerLeaseIsIdempotentAndNeverRemovesLiveReference() {
        assertFalse(helper.retainObserverFeed("observer","NF42",true));
        helper.subscribeToScrip("NF42");
        assertTrue(helper.retainObserverFeed("observer","NF42",true));
        assertTrue(helper.retainObserverFeed("observer","NF42",true));
        connector.lastMessages.clear();helper.releaseObserverFeed("observer");helper.releaseObserverFeed("observer");
        assertTrue(helper.getActiveScripKeys().contains("NF42"));assertTrue(connector.lastMessages.isEmpty());
        helper.unsubscribeFromScrip("NF42");assertFalse(helper.getActiveScripKeys().contains("NF42"));
    }
    @Test void resetInvalidatesObserverOwnershipBeforeLiveFeedsAreRecreated() {
        helper.subscribeToScrip("NF42");helper.retainObserverFeed("observer","NF42",true);
        helper.reset();helper.restoreOwnedLtpFeeds();helper.subscribeToScrip("NF42");connector.lastMessages.clear();
        helper.releaseObserverFeed("observer");assertTrue(helper.getActiveScripKeys().contains("NF42"));assertTrue(connector.lastMessages.isEmpty());
    }

    @Test void onlyObserverReferencesAreIdentifiedForFallbackPollingExclusion() {
        helper.subscribeToScrip("NF42");helper.retainObserverFeed("observer","NF42",true);
        assertTrue(helper.getObserverOnlyScripKeys().isEmpty());
        helper.unsubscribeFromScrip("NF42");assertTrue(helper.getObserverOnlyScripKeys().contains("NF42"));
        helper.subscribeToScrip("NF42");assertTrue(helper.getObserverOnlyScripKeys().isEmpty());
        helper.releaseObserverFeed("observer");assertTrue(helper.getActiveScripKeys().contains("NF42"));
    }

    @Test void namedLtpDemandIsIdempotentAndSharedWithOrdinaryWatchers() {
        helper.retainLtpFeed("strategy:1","NC20000");helper.retainLtpFeed("strategy:1","NC20000");
        helper.retainLtpFeed("strategy:2","NC20000");helper.subscribeToScripLtp("NC20000");
        assertEquals(1,connector.lastMessages.size());
        helper.releaseLtpFeed("strategy:1");helper.releaseLtpFeed("strategy:2");
        assertEquals(1,connector.lastMessages.size());assertTrue(helper.getActiveScripKeys().contains("NC20000"));
        assertTrue(helper.unsubscribeFromScripLtp("NC20000"));assertEquals(2,connector.lastMessages.size());
    }
    @Test void disconnectedLeaseRestoresExactlyOnceAndNeedsANewTickAfterReconnect() {
        connector.connected=false;helper.retainLtpFeed("strategy:1","NC20000");
        assertTrue(connector.lastMessages.isEmpty());assertEquals("DISCONNECTED",helper.ltpFeedStatus("NC20000").get("state"));
        connector.connected=true;helper.reset();helper.restoreOwnedLtpFeeds();helper.restoreOwnedLtpFeeds();
        assertEquals(1,connector.lastMessages.size());assertEquals("WAITING_FOR_TICKS",helper.ltpFeedStatus("NC20000").get("state"));
        helper.recordLtpReceived("NC20000",java.time.Instant.now().minusMillis(1));
        assertEquals("RECEIVING",helper.ltpFeedStatus("NC20000").get("state"));
        helper.connectionLost();assertEquals("DISCONNECTED",helper.ltpFeedStatus("NC20000").get("state"));
        helper.reset();helper.restoreOwnedLtpFeeds();assertEquals(2,connector.lastMessages.size());
        assertEquals("WAITING_FOR_TICKS",helper.ltpFeedStatus("NC20000").get("state"));
        helper.recordLtpReceived("NC20000",java.time.Instant.now().minusSeconds(6));
        assertEquals("STALE",helper.ltpFeedStatus("NC20000").get("state"));
        helper.releaseLtpFeed("strategy:1");assertEquals("NOT_REQUESTED",helper.ltpFeedStatus("NC20000").get("state"));
    }
    @Test void ownedDemandDoesNotGetMistakenForObserverOnlyTraffic() {
        helper.subscribeToScripLtp("NC20000");helper.retainObserverFeed("shadow","NC20000",false);
        helper.unsubscribeFromScripLtp("NC20000");assertTrue(helper.getObserverOnlyScripKeys().contains("NC20000"));
        helper.retainLtpFeed("strategy:1","NC20000");assertFalse(helper.getObserverOnlyScripKeys().contains("NC20000"));
        helper.releaseObserverFeed("shadow");assertTrue(helper.getActiveScripKeys().contains("NC20000"));
        helper.releaseLtpFeed("strategy:1");assertFalse(helper.getActiveScripKeys().contains("NC20000"));
    }

    @Test void failedFeedSendRemainsPendingAndRetryDoesNotAddAnotherOwner() {
        connector.failSend=true;helper.retainLtpFeed("strategy:1","NC20000");
        assertEquals("PENDING_SUBSCRIBE",helper.ltpFeedStatus("NC20000").get("state"));assertTrue(connector.lastMessages.isEmpty());
        connector.failSend=false;helper.retainLtpFeed("strategy:1","NC20000");
        assertEquals(1,connector.lastMessages.size());assertEquals(java.util.List.of("strategy:1"),helper.ltpFeedStatus("NC20000").get("owners"));
        helper.recordLtpReceived("NC20000",java.time.Instant.now());helper.releaseLtpFeed("strategy:1");
        helper.retainLtpFeed("strategy:1","NC20000");assertEquals("WAITING_FOR_TICKS",helper.ltpFeedStatus("NC20000").get("state"));
    }

    private static class TestConnector extends WebSocketConnector {
        private final java.util.List<String> lastMessages = new java.util.ArrayList<>();

        boolean connected=true,failSend=false;
        @Override public boolean isConnected(){return connected;}
        @Override public boolean trySend(String message){if(!connected||failSend)return false;lastMessages.add(message);return true;}
        @Override public void send(String message){trySend(message);}
    }
}
