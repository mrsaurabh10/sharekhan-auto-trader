package org.com.sharekhan.repository;
import org.com.sharekhan.entity.BrokerExitSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface BrokerExitSubmissionRepository extends JpaRepository<BrokerExitSubmission,String> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="INSERT INTO broker_exit_submissions (id,trade_id,credential_id,generation,created_at,confirmed_inactive) VALUES (:id,:trade,:credential,:generation,:created,false)",nativeQuery=true)
    void insertClaim(@org.springframework.data.repository.query.Param("id") String id,
        @org.springframework.data.repository.query.Param("trade") Long trade,
        @org.springframework.data.repository.query.Param("credential") Long credential,
        @org.springframework.data.repository.query.Param("generation") int generation,
        @org.springframework.data.repository.query.Param("created") java.time.Instant created);
    Optional<BrokerExitSubmission> findTopByTradeIdAndCredentialIdOrderByGenerationDesc(Long trade,Long credential);
}
