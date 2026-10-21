package org.com.sharekhan.repository;
import org.com.sharekhan.entity.ExitChaseControl;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ExitChaseControlRepository extends JpaRepository<ExitChaseControl,Long> {
    List<ExitChaseControl> findByActiveTrue();
    List<ExitChaseControl> findByAttentionIsNotNullAndNotificationDeliveredFalse();
}
