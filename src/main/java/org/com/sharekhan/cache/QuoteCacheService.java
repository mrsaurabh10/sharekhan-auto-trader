package org.com.sharekhan.cache;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory cache for best bid/ask quotes received from the Sharekhan streaming feed.
 * Keeps the latest snapshot per scrip along with derived metrics that are useful for spread checks.
 */
@Service
@Slf4j
public class QuoteCacheService {

    private final Map<Integer, QuoteSnapshot> quotes = new ConcurrentHashMap<>();

    @Getter
    @Builder
    @ToString
    public static class QuoteSnapshot {
        private final Integer scripCode;
        private final Double bestBid;
        private final Double bestAsk;
        private final Double lastTradedPrice;
        private final Double midPrice;
        private final Double spreadAbsolute;
        private final Double spreadPercent;
        private final Instant updatedAt;

        public boolean hasBook() {
            return bestBid != null && bestAsk != null && bestBid > 0 && bestAsk > 0;
        }
    }

    public void updateQuote(int scripCode, Double bestBid, Double bestAsk, Double ltp) {
        if (bestBid == null && bestAsk == null && ltp == null) {
            return;
        }

        quotes.compute(scripCode, (code, existing) -> mergeSnapshot(code, existing, bestBid, bestAsk, ltp));
    }

    private QuoteSnapshot mergeSnapshot(int scripCode,
                                        QuoteSnapshot existing,
                                        Double newBid,
                                        Double newAsk,
                                        Double newLtp) {

        Double bid = coalesce(newBid, existing != null ? existing.getBestBid() : null);
        Double ask = coalesce(newAsk, existing != null ? existing.getBestAsk() : null);
        Double ltp = coalesce(newLtp, existing != null ? existing.getLastTradedPrice() : null);

        Double mid = null;
        Double spreadAbs = null;
        Double spreadPct = null;

        if (bid != null && ask != null && bid > 0 && ask > 0) {
            mid = (bid + ask) / 2.0d;
            spreadAbs = Math.max(0d, ask - bid);
            if (mid != 0) {
                spreadPct = (spreadAbs / mid) * 100.0d;
            }
        } else if (bid != null && bid > 0 && ltp != null && ltp > 0) {
            mid = (bid + ltp) / 2.0d;
        } else if (ask != null && ask > 0 && ltp != null && ltp > 0) {
            mid = (ask + ltp) / 2.0d;
        } else if (ltp != null) {
            mid = ltp;
        }

        Instant updatedAt = Instant.now();

        QuoteSnapshot snapshot = QuoteSnapshot.builder()
                .scripCode(scripCode)
                .bestBid(bid)
                .bestAsk(ask)
                .lastTradedPrice(ltp)
                .midPrice(mid)
                .spreadAbsolute(spreadAbs)
                .spreadPercent(spreadPct)
                .updatedAt(updatedAt)
                .build();

        if (log.isDebugEnabled()) {
            log.debug("Updated quote cache for {}: {}", scripCode, snapshot);
        }
        return snapshot;
    }

    private Double coalesce(Double candidate, Double fallback) {
        return candidate != null && Double.isFinite(candidate) && candidate > 0 ? candidate : fallback;
    }

    public Optional<QuoteSnapshot> getSnapshot(int scripCode) {
        return Optional.ofNullable(quotes.get(scripCode));
    }

    public boolean isStale(QuoteSnapshot snapshot, Duration maxAge) {
        if (snapshot == null || snapshot.getUpdatedAt() == null) {
            return true;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        return snapshot.getUpdatedAt().isBefore(cutoff);
    }
}

