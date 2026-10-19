package org.com.sharekhan.onboarding;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, Long> {
    // A refresh renews prior consent; it must never create consent or undo disconnect.
    @Modifying(flushAutomatically = true)
    @Query("""
        update BrokerConnection c set c.verifiedUntil = :expiry, c.version = c.version + 1
        where c.credentialId = :credentialId and c.verifiedUntil > :disconnected
          and c.verifiedUntil <> :expiry
          and exists (select b.id from BrokerCredentialsEntity b
                      where b.id = c.credentialId and b.active = true
                        and lower(b.brokerName) = lower(:broker))
        """)
    int renewVerifiedConnection(@Param("credentialId") Long credentialId,
                                @Param("broker") String broker,
                                @Param("expiry") Instant expiry,
                                @Param("disconnected") Instant disconnected);
}
