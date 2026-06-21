package org.com.sharekhan.repository;

import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BacktestReplayResultRepository extends JpaRepository<BacktestReplayResultEntity, Long> {

    Optional<BacktestReplayResultEntity> findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
            Long tradeSetupId,
            String interval,
            String triggerPricePolicy,
            String squareOffTime);

    List<BacktestReplayResultEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
}
