package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BrokerCredentialsService {

    private final BrokerCredentialsRepository repository;

    public Optional<BrokerCredentialsEntity> findActiveForUserAndBroker(String brokerName, Long appUserId) {
        Optional<BrokerCredentialsEntity> opt = repository.findTopByBrokerNameAndAppUserIdAndActiveTrue(brokerName, appUserId);
        if (opt.isEmpty()) {
            opt = repository.findTopByBrokerNameAndAppUserId(brokerName, appUserId);
        }
        return opt;
    }

    public java.util.List<BrokerCredentialsEntity> findAllForBroker(String brokerName) {
        return repository.findAllByBrokerName(brokerName);
    }

    // Find broker credentials by broker name and the broker's customer id
    public Optional<BrokerCredentialsEntity> findForBrokerAndCustomer(String brokerName, Long customerId) {
        if (customerId == null) return Optional.empty();
        Optional<BrokerCredentialsEntity> opt = repository.findTopByBrokerNameAndCustomerIdAndActiveTrue(brokerName, customerId);
        if (opt.isPresent()) return opt;
        var list = repository.findAllByBrokerNameAndCustomerId(brokerName, customerId);
        if (list != null && !list.isEmpty()) return Optional.of(list.get(0));
        return Optional.empty();
    }

    // Persist broker credentials (used by AuthController)
    public BrokerCredentialsEntity save(BrokerCredentialsEntity entity) {
        if (entity == null) return null;
        return repository.save(entity);
    }

}
