package org.com.sharekhan.repository;

import org.com.sharekhan.entity.AccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccessTokenRepository extends JpaRepository<AccessTokenEntity, Long> {

    AccessTokenEntity findTopByOrderByExpiryDesc();

    void deleteAll();
}
