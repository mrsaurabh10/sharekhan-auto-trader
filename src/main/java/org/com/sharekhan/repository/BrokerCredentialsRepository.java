package org.com.sharekhan.repository;

import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrokerCredentialsRepository extends JpaRepository<BrokerCredentialsEntity, Long> {
    Optional<BrokerCredentialsEntity> findTopByBrokerNameAndCustomerIdAndActiveTrue(String brokerName, Long customerId);
    Optional<BrokerCredentialsEntity> findTopByBrokerNameAndCustomerId(String brokerName, Long customerId);
    List<BrokerCredentialsEntity> findByCustomerId(Long customerId);
    List<BrokerCredentialsEntity> findAllByBrokerName(String brokerName);
}
