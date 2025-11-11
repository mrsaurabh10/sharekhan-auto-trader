package org.com.sharekhan.repository;

import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrokerCredentialsRepository extends JpaRepository<BrokerCredentialsEntity, Long> {
    Optional<BrokerCredentialsEntity> findTopByBrokerNameAndAppUserIdAndActiveTrue(String brokerName, Long appUserId);
    Optional<BrokerCredentialsEntity> findTopByBrokerNameAndAppUserId(String brokerName, Long appUserId);
    List<BrokerCredentialsEntity> findByAppUserId(Long appUserId);
    List<BrokerCredentialsEntity> findAllByBrokerName(String brokerName);

    // find the active credentials for a given broker customer id (customerId is the broker's customer identifier)
    Optional<BrokerCredentialsEntity> findTopByBrokerNameAndCustomerIdAndActiveTrue(String brokerName, Long customerId);
    List<BrokerCredentialsEntity> findAllByBrokerNameAndCustomerId(String brokerName, Long customerId);

    // legacy/misc helpers removed
}
