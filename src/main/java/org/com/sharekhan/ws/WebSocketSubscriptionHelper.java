package org.com.sharekhan.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSubscriptionHelper implements WebSocketSubscriptionService {

    // Reference counts per feed mode + scrip key (e.g., full|NF42120, ltp|NC20000).
    private final ConcurrentHashMap<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    private final WebSocketConnector connector;

    public void reset() {
        log.info("🧹 Resetting WebSocket subscription reference counts");
        refCounts.clear();
    }

    @Override
    public boolean subscribeToScrip(String scripKey) {
        return subscribe(scripKey, "full");
    }

    @Override
    public boolean subscribeToScripLtp(String scripKey) {
        return subscribe(scripKey, "ltp");
    }

    private boolean subscribe(String scripKey, String feedMode) {
        String refKey = refKey(feedMode, scripKey);
        final AtomicInteger[] created = new AtomicInteger[1];
        refCounts.compute(refKey, (k, v) -> {
            if (v == null) {
                created[0] = new AtomicInteger(1);
                return created[0];
            }
            v.incrementAndGet();
            return v;
        });

        // If we created a new counter, this is the first subscription (0 -> 1)
        if (created[0] != null) {
            log.info("📡 Subscribing to {} with {} feed", scripKey, feedMode);
            connector.send("{\"action\":\"feed\",\"key\":[\"" + feedMode + "\"],\"value\":[\"" + scripKey + "\"]}");
            return true;
        } else {
            log.debug("Already subscribed to {} feed for {}, ref++ -> {}", feedMode, scripKey, refCounts.get(refKey).get());
            return false;
        }
    }

    @Override
    public void subscribeToAck(String customerId) {
        connector.send("{\"action\":\"ack\",\"key\":[\"\"],\"value\":[\"" + customerId + "\"]}");
        log.info("📡 Subscribing Ack to {}", customerId);
    }

    @Override
    public boolean unsubscribeFromScrip(String scripKey) {
        return unsubscribe(scripKey, "full");
    }

    @Override
    public boolean unsubscribeFromScripLtp(String scripKey) {
        return unsubscribe(scripKey, "ltp");
    }

    private boolean unsubscribe(String scripKey, String feedMode) {
        String refKey = refKey(feedMode, scripKey);
        final boolean[] shouldUnsub = {false};
        refCounts.compute(refKey, (k, v) -> {
            if (v == null) {
                // nothing to do
                return null;
            }
            int n = v.decrementAndGet();
            if (n <= 0) {
                shouldUnsub[0] = true;
                return null; // remove entry
            }
            return v;
        });

        if (shouldUnsub[0]) {
            log.info("🛑 Unsubscribing from {} {} feed", scripKey, feedMode);
            connector.send("{\"action\":\"unsubscribe\",\"key\":[\"" + feedMode + "\"],\"value\":[\"" + scripKey + "\"]}");
            return true;
        } else {
            if (!refCounts.containsKey(refKey)) {
                log.debug("⚠️ Not currently subscribed to {} feed: {}", feedMode, scripKey);
            } else {
                log.debug("Still {} watcher(s) for {} {} feed - skip unsubscribe", refCounts.get(refKey).get(), scripKey, feedMode);
            }
            return false;
        }
    }

    @Override
    public Set<String> getActiveScripKeys() {
        return refCounts.keySet().stream()
                .map(WebSocketSubscriptionHelper::scripKey)
                .collect(Collectors.toSet());
    }

    private static String refKey(String feedMode, String scripKey) {
        return feedMode + "|" + scripKey;
    }

    private static String scripKey(String refKey) {
        int separator = refKey.indexOf('|');
        return separator >= 0 ? refKey.substring(separator + 1) : refKey;
    }
}
