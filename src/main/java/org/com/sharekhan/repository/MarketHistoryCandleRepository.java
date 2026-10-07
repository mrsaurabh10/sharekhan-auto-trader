package org.com.sharekhan.repository;

import org.com.sharekhan.entity.MarketHistoryCandleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketHistoryCandleRepository extends JpaRepository<MarketHistoryCandleEntity, String> {
    List<MarketHistoryCandleEntity> findBySymbolAndCandleTimeGreaterThanEqualAndCandleTimeLessThanOrderByCandleTime(
            String symbol, LocalDateTime from, LocalDateTime to);
}
