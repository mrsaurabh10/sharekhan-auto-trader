package org.com.sharekhan.repository;

import org.com.sharekhan.entity.AccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessTokenRepository extends JpaRepository<AccessTokenEntity, Long> {

    AccessTokenEntity findTopByOrderByExpiryDesc();

    void deleteAll();

    AccessTokenEntity findTopByBrokerNameOrderByExpiryDesc(String brokerName);

    // delete tokens for a specific broker
    void deleteAllByBrokerName(String brokerName);

    // broker-customer-scoped methods (mapped to AccessTokenEntity.brokerCredentialsId)
    AccessTokenEntity findTopByBrokerNameAndBrokerCredentialsIdOrderByExpiryDesc(String brokerName, Long brokerCredentialsId);

    void deleteAllByBrokerNameAndBrokerCredentialsId(String brokerName, Long brokerCredentialsId);

    // user-scoped methods (mapped to AccessTokenEntity.userId)
    AccessTokenEntity findTopByBrokerNameAndUserIdOrderByExpiryDesc(String brokerName, Long userId);

    void deleteAllByBrokerNameAndUserId(String brokerName, Long userId);

    // Additional helpers
    List<AccessTokenEntity> findAllByBrokerName(String brokerName);
    List<AccessTokenEntity> findAllByBrokerNameAndBrokerCredentialsId(String brokerName, Long brokerCredentialsId);
    List<AccessTokenEntity> findAllByBrokerNameAndUserId(String brokerName, Long userId);

}
