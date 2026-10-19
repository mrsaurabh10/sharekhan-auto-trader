package org.com.sharekhan.repository;
import org.com.sharekhan.entity.ExpiryStraddleSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.*;
import java.util.List;
public interface ExpiryStraddleSessionRepository extends JpaRepository<ExpiryStraddleSession,String> {
    List<ExpiryStraddleSession> findByTradingDayLessThanEqual(LocalDate day);
    List<ExpiryStraddleSession> findByProfitTargetPerLotIsNotNullAndTradingDayLessThanEqual(LocalDate day);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true,flushAutomatically=true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update ExpiryStraddleSession s set s.profitExitRequested=true, s.message=:message where s.id=:id and s.profitExitRequested=false")
    int claimProfitExit(@org.springframework.data.repository.query.Param("id") String id,
                        @org.springframework.data.repository.query.Param("message") String message);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically=true,flushAutomatically=true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update ExpiryStraddleSession s set s.abortRequested=case when s.abortRequested=true or :abort=true then true else false end, s.message=:message where s.id=:id and s.profitExitRequested=false")
    int recordOutcome(@org.springframework.data.repository.query.Param("id") String id,
                      @org.springframework.data.repository.query.Param("abort") boolean abort,
                      @org.springframework.data.repository.query.Param("message") String message);
}
