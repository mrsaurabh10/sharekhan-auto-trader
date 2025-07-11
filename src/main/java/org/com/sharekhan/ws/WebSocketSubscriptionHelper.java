package org.com.sharekhan.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSubscriptionHelper implements WebSocketSubscriptionService {

    // 🧠 Cache to track active subscriptions (e.g., NC2885, NF42120, etc.)
    private final Set<String> activeLtpSubscriptions = ConcurrentHashMap.newKeySet();


    private final WebSocketConnector connector;

    @Override
    public void subscribeToScrip(String scripKey) {
        if (activeLtpSubscriptions.contains(scripKey)) return;
        log.info("📡 Subscribing to {}", scripKey);
        activeLtpSubscriptions.add(scripKey);
        connector.send("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"" + scripKey + "\"]}");
    }

    @Override
    public void subscribeToAck(String customerId) {
        connector.send("{\"action\":\"ack\",\"key\":[\"\"],\"value\":[\"" + customerId + "\"]}");
        log.info("📡 Subscribing Ack to {}", customerId);
    }

    @Override
    public void unsubscribeFromScrip(String scripKey) {
        if (!activeLtpSubscriptions.contains(scripKey)) {
            log.debug("⚠️ Not currently subscribed to feed: {}", scripKey);
            return;
        }

        log.info("🛑 Unsubscribing from {}", scripKey);
        connector.send("{\"action\":\"unsubscribe\",\"key\":[\"ltp\"],\"value\":[\"" + scripKey + "\"]}");
        activeLtpSubscriptions.remove(scripKey);
    }
}
