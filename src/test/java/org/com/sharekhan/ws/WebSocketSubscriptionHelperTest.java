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

    private static class TestConnector extends WebSocketConnector {
        private final java.util.List<String> lastMessages = new java.util.ArrayList<>();

        @Override
        public void send(String message) {
            lastMessages.add(message);
        }
    }
}
