package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrokerRoutingServiceTest {

    @Test
    void fanOutKeepsLegacySharekhanButRequiresMstockTradingEnabled() {
        BrokerCredentialsEntity sharekhan = credential(1L, 10L, Broker.SHAREKHAN, true, null, null);
        BrokerCredentialsEntity disabledMstock = credential(2L, 10L, Broker.MSTOCK, true, null, null);
        BrokerCredentialsEntity enabledMstock = credential(3L, 11L, Broker.MSTOCK, true, true, null);
        BrokerRoutingService service = new BrokerRoutingService(repository(List.of(sharekhan, disabledMstock, enabledMstock)));

        List<BrokerCredentialsEntity> targets = service.resolveFanOutTargets(new TriggerRequest());

        assertEquals(List.of(1L, 3L), targets.stream().map(BrokerCredentialsEntity::getId).toList());
    }

    @Test
    void explicitTargetCanSelectActiveMstockWithoutTradingEnabledFlag() {
        BrokerCredentialsEntity mstock = credential(7L, 10L, Broker.MSTOCK, true, null, null);
        TriggerRequest request = new TriggerRequest();
        request.setTargetBrokerCredentialsIds(List.of(7L));
        BrokerRoutingService service = new BrokerRoutingService(repository(List.of(mstock)));

        List<BrokerCredentialsEntity> targets = service.resolveFanOutTargets(request);

        assertEquals(List.of(7L), targets.stream().map(BrokerCredentialsEntity::getId).toList());
    }

    @Test
    void selectedModeCanTargetBrokerName() {
        BrokerCredentialsEntity mstock = credential(7L, 10L, Broker.MSTOCK, true, true, null);
        TriggerRequest request = new TriggerRequest();
        request.setRoutingMode("SELECTED");
        request.setBrokerName(Broker.MSTOCK.getDisplayName());
        BrokerRoutingService service = new BrokerRoutingService(repository(List.of(mstock)));

        List<BrokerCredentialsEntity> targets = service.resolveFanOutTargets(request);

        assertEquals(List.of(7L), targets.stream().map(BrokerCredentialsEntity::getId).toList());
    }

    @Test
    void defaultModePicksUsersDefaultOrderBroker() {
        BrokerCredentialsEntity sharekhan = credential(1L, 10L, Broker.SHAREKHAN, true, null, null);
        BrokerCredentialsEntity mstock = credential(2L, 10L, Broker.MSTOCK, true, true, true);
        TriggerRequest request = new TriggerRequest();
        request.setRoutingMode("DEFAULT");
        request.setUserId(10L);
        BrokerRoutingService service = new BrokerRoutingService(repository(List.of(sharekhan, mstock)));

        List<BrokerCredentialsEntity> targets = service.resolveFanOutTargets(request);

        assertEquals(List.of(2L), targets.stream().map(BrokerCredentialsEntity::getId).toList());
    }

    private static BrokerCredentialsEntity credential(Long id,
                                                       Long appUserId,
                                                       Broker broker,
                                                       Boolean active,
                                                       Boolean tradingEnabled,
                                                       Boolean defaultForOrders) {
        return BrokerCredentialsEntity.builder()
                .id(id)
                .appUserId(appUserId)
                .brokerName(broker.getDisplayName())
                .active(active)
                .tradingEnabled(tradingEnabled)
                .defaultForOrders(defaultForOrders)
                .build();
    }

    private static BrokerCredentialsRepository repository(List<BrokerCredentialsEntity> credentials) {
        return (BrokerCredentialsRepository) Proxy.newProxyInstance(
                BrokerCredentialsRepository.class.getClassLoader(),
                new Class<?>[]{BrokerCredentialsRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> credentials;
                    case "findById" -> {
                        Long id = (Long) args[0];
                        yield credentials.stream()
                                .filter(c -> id.equals(c.getId()))
                                .findFirst();
                    }
                    case "findByAppUserId" -> {
                        Long appUserId = (Long) args[0];
                        yield credentials.stream()
                                .filter(c -> appUserId.equals(c.getAppUserId()))
                                .toList();
                    }
                    case "findAllByBrokerName" -> {
                        String brokerName = (String) args[0];
                        yield credentials.stream()
                                .filter(c -> c.getBrokerName() != null && c.getBrokerName().equalsIgnoreCase(brokerName))
                                .toList();
                    }
                    case "toString" -> "BrokerCredentialsRepositoryFake";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == List.class) return List.of();
        if (returnType == Optional.class) return Optional.empty();
        return null;
    }
}
