package org.com.sharekhan.repository;
import org.com.sharekhan.entity.ExpiryStraddleSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.*;
import java.util.List;
public interface ExpiryStraddleSessionRepository extends JpaRepository<ExpiryStraddleSession,String> {
    List<ExpiryStraddleSession> findByTradingDayLessThanEqual(LocalDate day);
}
