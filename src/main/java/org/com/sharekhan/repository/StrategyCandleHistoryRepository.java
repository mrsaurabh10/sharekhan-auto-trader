package org.com.sharekhan.repository;

import org.com.sharekhan.entity.StrategyCandleHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StrategyCandleHistoryRepository extends JpaRepository<StrategyCandleHistoryEntity, String> {
    List<StrategyCandleHistoryEntity> findTop250ByInstrumentKeyOrderByCandleTimeDesc(String instrumentKey);
    void deleteByInstrumentKeyAndCandleTimeBefore(String instrumentKey, LocalDateTime cutoff);
}
