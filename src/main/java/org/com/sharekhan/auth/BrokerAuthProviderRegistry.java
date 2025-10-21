package org.com.sharekhan.auth;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.enums.Broker;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BrokerAuthProviderRegistry {

    private final List<BrokerAuthProvider> providers;

    // lazy-initialized map for quick lookup
    private Map<Broker, BrokerAuthProvider> providerMap;

    private void ensureMap() {
        if (providerMap == null) {
            providerMap = new HashMap<>();
            for (BrokerAuthProvider p : providers) {
                providerMap.put(p.getBroker(), p);
            }
        }
    }

    public BrokerAuthProvider getProvider(Broker broker) {
        ensureMap();
        return providerMap.get(broker);
    }

    public List<BrokerAuthProvider> getAllProviders() {
        return providers;
    }
}

