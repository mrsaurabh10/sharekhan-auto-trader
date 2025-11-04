package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BrokerCredentialsService {

    private final BrokerCredentialsRepository repository;
    private final CryptoService cryptoService;

    public Optional<BrokerCredentialsEntity> findForBrokerAndCustomer(String brokerName, Long customerId) {
        Optional<BrokerCredentialsEntity> opt = repository.findTopByBrokerNameAndCustomerId(brokerName, customerId);
        opt.ifPresent(this::decryptFields);
        return opt;
    }

    public BrokerCredentialsEntity save(BrokerCredentialsEntity entity) {
        // encrypt sensitive fields before saving
        BrokerCredentialsEntity copy = BrokerCredentialsEntity.builder()
                .id(entity.getId())
                .brokerName(entity.getBrokerName())
                .customerId(entity.getCustomerId())
                .apiKey(entity.getApiKey() != null ? cryptoService.encrypt(entity.getApiKey()) : null)
                .brokerUsername(entity.getBrokerUsername() != null ? cryptoService.encrypt(entity.getBrokerUsername()) : null)
                .brokerPassword(entity.getBrokerPassword() != null ? cryptoService.encrypt(entity.getBrokerPassword()) : null)
                .build();
        BrokerCredentialsEntity saved = repository.save(copy);
        decryptFields(saved);
        return saved;
    }

    private void decryptFields(BrokerCredentialsEntity entity) {
        if (entity.getApiKey() != null) {
            try { entity.setApiKey(cryptoService.decrypt(entity.getApiKey())); } catch (Exception ignored) {}
        }
        if (entity.getBrokerUsername() != null) {
            try { entity.setBrokerUsername(cryptoService.decrypt(entity.getBrokerUsername())); } catch (Exception ignored) {}
        }
        if (entity.getBrokerPassword() != null) {
            try { entity.setBrokerPassword(cryptoService.decrypt(entity.getBrokerPassword())); } catch (Exception ignored) {}
        }
    }
}
