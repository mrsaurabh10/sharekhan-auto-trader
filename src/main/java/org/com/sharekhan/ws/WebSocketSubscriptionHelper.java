package org.com.sharekhan.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSubscriptionHelper implements WebSocketSubscriptionService {

    // Reference counts per scrip feed key (e.g., NC2885, NF42120, etc.)
    private final ConcurrentHashMap<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    private final WebSocketConnector connector;

    public void reset() {
        log.info("🧹 Resetting WebSocket subscription reference counts");
        refCounts.clear();
    }

    @Override
    public boolean subscribeToScrip(String scripKey) {
        final AtomicInteger[] created = new AtomicInteger[1];
        refCounts.compute(scripKey, (k, v) -> {
            if (v == null) {
                created[0] = new AtomicInteger(1);
                return created[0];
            }
            v.incrementAndGet();
            return v;
        });

        // If we created a new counter, this is the first subscription (0 -> 1)
        if (created[0] != null) {
            log.info("📡 Subscribing to {}", scripKey);
            connector.send("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"" + scripKey + "\"]}");
            return true;
        } else {
            log.debug("Already subscribed to {}, ref++ -> {}", scripKey, refCounts.get(scripKey).get());
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
        final boolean[] shouldUnsub = {false};
        refCounts.compute(scripKey, (k, v) -> {
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
            log.info("🛑 Unsubscribing from {}", scripKey);
            connector.send("{\"action\":\"unsubscribe\",\"key\":[\"ltp\"],\"value\":[\"" + scripKey + "\"]}");
            return true;
        } else {
            if (!refCounts.containsKey(scripKey)) {
                log.debug("⚠️ Not currently subscribed to feed: {}", scripKey);
            } else {
                log.debug("Still {} watcher(s) for {} — skip unsubscribe", refCounts.get(scripKey).get(), scripKey);
            }
            return false;
        }
    }

    @Override
    public Set<String> getActiveScripKeys() {
        return refCounts.keySet();
    }
}
