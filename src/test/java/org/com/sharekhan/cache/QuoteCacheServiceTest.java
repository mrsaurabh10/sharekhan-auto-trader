package org.com.sharekhan.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class QuoteCacheServiceTest {
    @org.junit.jupiter.api.Test void independentSharekhanBookSurvivesOtherProviderUpdates(){
        var cache=new QuoteCacheService();
        cache.recordQuote(1,99d,100d,99.5,"SHAREKHAN_WS","NF","scrip:1",130d,65d);
        cache.recordQuote(1,98d,101d,100d,"SHOONYA","NF","other:1",65d,65d);
        org.assertj.core.api.Assertions.assertThat(cache.getSnapshot(1).orElseThrow().getBookSource()).isEqualTo("SHOONYA");
        var stream=cache.getSharekhanSnapshot(1).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stream.getBookSource()).isEqualTo("SHAREKHAN_WS");
        org.assertj.core.api.Assertions.assertThat(stream.getBestBid()).isEqualTo(99);
        org.assertj.core.api.Assertions.assertThat(stream.getBidQuantity()).isEqualTo(130);
        cache.recordQuote(1,99.25,null,99.5,"SHAREKHAN_WS","NF","scrip:1",65d,null);
        org.assertj.core.api.Assertions.assertThat(cache.getSharekhanSnapshot(1).orElseThrow().isCoherentBook()).isFalse();
        cache.clear();org.assertj.core.api.Assertions.assertThat(cache.getSharekhanSnapshot(1)).isEmpty();
    }


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
        QuoteCacheService.QuoteSnapshot initial = service.getSnapshot(202)
                .orElseThrow(() -> new AssertionError("Snapshot missing"));
        service.recordQuote(202, null, null, 201.5);

        QuoteCacheService.QuoteSnapshot snapshot = service.getSnapshot(202)
                .orElseThrow(() -> new AssertionError("Snapshot missing"));

        assertEquals(198.0, snapshot.getBestBid());
        assertEquals(202.0, snapshot.getBestAsk());
        assertEquals(200.0, snapshot.getMidPrice());
        assertEquals(initial.getLastBookAt(), snapshot.getLastBookAt());
        assertFalse(service.isLtpStale(snapshot, Duration.ofSeconds(1)));
        assertTrue(service.isBookStale(snapshot, Duration.ZERO));
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
