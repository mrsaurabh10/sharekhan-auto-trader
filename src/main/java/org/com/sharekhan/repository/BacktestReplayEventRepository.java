package org.com.sharekhan.repository;

import org.com.sharekhan.entity.BacktestReplayEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BacktestReplayEventRepository extends JpaRepository<BacktestReplayEventEntity, Long> {

    @Transactional
    void deleteByResultId(Long resultId);

    List<BacktestReplayEventEntity> findByResultIdOrderByEventIndex(Long resultId);
}
