package org.com.sharekhan.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class QuoteCacheServiceTest {

    private final QuoteCacheService service = new QuoteCacheService();

    @Test
    void recordQuoteStoresBestBidAskAndSpread() {
        service.recordQuote(101, 99.0, 101.0, 100.0);

        QuoteCacheService.QuoteSnapshot snapshot = service.getSnapshot(101)
                .orElseThrow(() -> new AssertionError("Snapshot missing"));

        assertEquals(99.0, snapshot.getBestBid());
        assertEquals(101.0, snapshot.getBestAsk());
        assertEquals(100.0, snapshot.getMidPrice());
        assertEquals(2.0, snapshot.getSpreadAbsolute());
        assertEquals(2.0, snapshot.getSpreadPercent(), 1e-6);
        assertFalse(service.isStale(snapshot, Duration.ofSeconds(1)));
    }

    @Test
    void recordQuoteWithPartialDataKeepsPreviousValues() {
        service.recordQuote(202, 198.0, 202.0, 200.0);
        service.recordQuote(202, null, null, 201.5);

        QuoteCacheService.QuoteSnapshot snapshot = service.getSnapshot(202)
                .orElseThrow(() -> new AssertionError("Snapshot missing"));

        assertEquals(198.0, snapshot.getBestBid());
        assertEquals(202.0, snapshot.getBestAsk());
        assertEquals(200.0, snapshot.getMidPrice());
    }

    @Test
    void snapshotIsMarkedStaleAfterThreshold() throws InterruptedException {
        service.recordQuote(303, 50.0, 52.0, 51.0);
        QuoteCacheService.QuoteSnapshot snapshot = service.getSnapshot(303)
                .orElseThrow(() -> new AssertionError("Snapshot missing"));

        Thread.sleep(5);

        assertTrue(service.isStale(snapshot, Duration.ofMillis(1)));
    }
}

