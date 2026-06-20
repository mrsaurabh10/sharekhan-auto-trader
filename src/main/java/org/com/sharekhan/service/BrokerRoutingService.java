package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BrokerRoutingService {

    private static final String ROUTING_DEFAULT = "DEFAULT";
    private static final String ROUTING_SELECTED = "SELECTED";

    private final BrokerCredentialsRepository brokerCredentialsRepository;

    public List<BrokerCredentialsEntity> resolveFanOutTargets(TriggerRequest request) {
        if (request == null) {
            return List.of();
        }

        List<BrokerCredentialsEntity> explicit = resolveExplicitTargets(request);
        if (!explicit.isEmpty()) {
            return explicit;
        }

        if (StringUtils.hasText(request.getBrokerName())) {
            return distinct(brokerCredentialsRepository.findAllByBrokerName(request.getBrokerName()).stream()
                    .filter(this::isActive)
                    .filter(this::isEnabledForAutomatedTrading)
                    .toList());
        }

        String routingMode = normalizeMode(request.getRoutingMode());
        if (ROUTING_SELECTED.equals(routingMode)) {
            return List.of();
        }

        if (ROUTING_DEFAULT.equals(routingMode)) {
            if (request.getUserId() != null) {
                return resolveDefaultForUser(request.getUserId())
                        .map(List::of)
                        .orElseGet(List::of);
            }
            List<BrokerCredentialsEntity> defaults = brokerCredentialsRepository.findAll().stream()
                    .filter(this::isActive)
                    .filter(this::isEnabledForAutomatedTrading)
                    .filter(c -> Boolean.TRUE.equals(c.getDefaultForOrders()))
                    .sorted(defaultSort())
                    .toList();
            if (!defaults.isEmpty()) {
                return distinct(defaults);
            }
        }

        return distinct(brokerCredentialsRepository.findAll().stream()
                .filter(this::isActive)
                .filter(this::isEnabledForAutomatedTrading)
                .sorted(defaultSort())
                .toList());
    }

    public Optional<BrokerCredentialsEntity> resolveDefaultForUser(Long appUserId) {
        if (appUserId == null) {
            return Optional.empty();
        }
        List<BrokerCredentialsEntity> credentials = brokerCredentialsRepository.findByAppUserId(appUserId);
        return choosePreferredBrokerCredential(credentials);
    }

    public Optional<BrokerCredentialsEntity> choosePreferredBrokerCredential(List<BrokerCredentialsEntity> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Optional.empty();
        }

        List<BrokerCredentialsEntity> active = credentials.stream()
                .filter(this::isActive)
                .toList();
        if (active.isEmpty()) {
            return credentials.stream().filter(Objects::nonNull).findFirst();
        }

        Optional<BrokerCredentialsEntity> defaultCredential = active.stream()
                .filter(this::isEnabledForAutomatedTrading)
                .filter(c -> Boolean.TRUE.equals(c.getDefaultForOrders()))
                .findFirst();
        if (defaultCredential.isPresent()) {
            return defaultCredential;
        }

        Optional<BrokerCredentialsEntity> activeSharekhan = active.stream()
                .filter(c -> isBroker(c, Broker.SHAREKHAN))
                .findFirst();
        if (activeSharekhan.isPresent()) {
            return activeSharekhan;
        }

        Optional<BrokerCredentialsEntity> enabled = active.stream()
                .filter(this::isEnabledForAutomatedTrading)
                .findFirst();
        if (enabled.isPresent()) {
            return enabled;
        }

        return active.stream().findFirst();
    }

    public boolean isEnabledForAutomatedTrading(BrokerCredentialsEntity credential) {
        if (!isActive(credential)) {
            return false;
        }
        if (credential.getTradingEnabled() != null) {
            return credential.getTradingEnabled();
        }
        Broker broker = parseBroker(credential.getBrokerName());
        return broker == Broker.SHAREKHAN || broker == Broker.SIMULATOR;
    }

    private List<BrokerCredentialsEntity> resolveExplicitTargets(TriggerRequest request) {
        List<BrokerCredentialsEntity> targets = new ArrayList<>();
        if (request.getBrokerCredentialsId() != null) {
            brokerCredentialsRepository.findById(request.getBrokerCredentialsId())
                    .filter(this::isActive)
                    .ifPresent(targets::add);
        }
        if (request.getTargetBrokerCredentialsIds() != null) {
            for (Long id : request.getTargetBrokerCredentialsIds()) {
                if (id == null) {
                    continue;
                }
                brokerCredentialsRepository.findById(id)
                        .filter(this::isActive)
                        .ifPresent(targets::add);
            }
        }
        return distinct(targets);
    }

    private List<BrokerCredentialsEntity> distinct(List<BrokerCredentialsEntity> credentials) {
        Map<Long, BrokerCredentialsEntity> byId = new LinkedHashMap<>();
        for (BrokerCredentialsEntity credential : credentials) {
            if (credential == null) {
                continue;
            }
            Long id = credential.getId();
            if (id != null) {
                byId.putIfAbsent(id, credential);
            }
        }
        return List.copyOf(byId.values());
    }

    private Comparator<BrokerCredentialsEntity> defaultSort() {
        return Comparator
                .comparing((BrokerCredentialsEntity c) -> c.getAppUserId() != null ? c.getAppUserId() : Long.MAX_VALUE)
                .thenComparing(c -> c.getId() != null ? c.getId() : Long.MAX_VALUE);
    }

    private String normalizeMode(String routingMode) {
        if (!StringUtils.hasText(routingMode)) {
            return "";
        }
        return routingMode.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isActive(BrokerCredentialsEntity credential) {
        return credential != null && !Boolean.FALSE.equals(credential.getActive());
    }

    private boolean isBroker(BrokerCredentialsEntity credential, Broker broker) {
        return credential != null && parseBroker(credential.getBrokerName()) == broker;
    }

    private Broker parseBroker(String brokerName) {
        try {
            return Broker.fromDisplayName(brokerName);
        } catch (Exception e) {
            return null;
        }
    }
}
