package org.com.sharekhan.service.broker;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.enums.Broker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BrokerServiceFactory {

    private final List<BrokerService> brokerServices;
    private Map<Broker, BrokerService> serviceMap;

    private void initMap() {
        if (serviceMap == null) {
            serviceMap = brokerServices.stream()
                    .collect(Collectors.toMap(BrokerService::getBroker, Function.identity()));
        }
    }

    public BrokerService getService(Broker broker) {
        initMap();
        return serviceMap.get(broker);
    }
    
    public BrokerService getService(String brokerName) {
        try {
            Broker broker = Broker.fromDisplayName(brokerName);
            return getService(broker);
        } catch (Exception e) {
            // Fallback or try valueOf
            try {
                Broker broker = Broker.valueOf(brokerName.toUpperCase());
                return getService(broker);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
