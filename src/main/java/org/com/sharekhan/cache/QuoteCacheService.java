package org.com.sharekhan.cache;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight cache for top-of-book quotes (best bid/ask) received from the broker stream.
 * Used to analyse spreads without affecting live order placement.
 */
@Service
@Slf4j
public class QuoteCacheService {

    private final Map<Integer, QuoteSnapshot> quotesByScrip = new ConcurrentHashMap<>();

    @Value
    @Builder
    public static class QuoteSnapshot {
        Integer scripCode;
        Double bestBid;
        Double bestAsk;
        Double lastTradedPrice;
        Double midPrice;
        Double spreadAbsolute;
        Double spreadPercent;
        Instant updatedAt;

        public boolean hasBook() {
            return bestBid != null && bestBid > 0 && bestAsk != null && bestAsk > 0;
        }
    }

    public void recordQuote(int scripCode, Double bestBid, Double bestAsk, Double ltp) {
        quotesByScrip.compute(scripCode, (code, existing) -> merge(existing, code, bestBid, bestAsk, ltp));
    }

    private QuoteSnapshot merge(QuoteSnapshot existing,
                                int scripCode,
                                Double newBid,
                                Double newAsk,
                                Double newLtp) {
        Double bid = coalesce(newBid, existing != null ? existing.getBestBid() : null);
        Double ask = coalesce(newAsk, existing != null ? existing.getBestAsk() : null);
        Double ltp = coalesce(newLtp, existing != null ? existing.getLastTradedPrice() : null);

        Double mid = null;
        Double spreadAbs = null;
        Double spreadPct = null;

        if (bid != null && bid > 0 && ask != null && ask > 0) {
            mid = (bid + ask) / 2.0;
            spreadAbs = Math.max(0d, ask - bid);
            if (mid != null && mid > 0) {
                spreadPct = (spreadAbs / mid) * 100.0;
            }
        } else if (ltp != null) {
            mid = ltp;
        }

        QuoteSnapshot snapshot = QuoteSnapshot.builder()
                .scripCode(scripCode)
                .bestBid(bid)
                .bestAsk(ask)
                .lastTradedPrice(ltp)
                .midPrice(mid)
                .spreadAbsolute(spreadAbs)
                .spreadPercent(spreadPct)
                .updatedAt(Instant.now())
                .build();

        if (log.isDebugEnabled()) {
            log.debug("Updated quote cache for {} -> {}", scripCode, snapshot);
        }
        return snapshot;
    }

    private Double coalesce(Double candidate, Double fallback) {
        if (candidate != null && Double.isFinite(candidate) && candidate > 0) {
            return candidate;
        }
        return fallback;
    }

    public Optional<QuoteSnapshot> getSnapshot(int scripCode) {
        return Optional.ofNullable(quotesByScrip.get(scripCode));
    }

    public boolean isStale(QuoteSnapshot snapshot, Duration maxAge) {
        if (snapshot == null || snapshot.getUpdatedAt() == null) {
            return true;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        return snapshot.getUpdatedAt().isBefore(cutoff);
    }

    public void clear() {
        quotesByScrip.clear();
    }
}

