package org.com.sharekhan.repository;

import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MarketauxEntitySentimentRepository extends JpaRepository<MarketauxEntitySentimentEntity, Long> {
    List<MarketauxEntitySentimentEntity> findTop500ByTradingDateOrderByCollectedAtDesc(LocalDate tradingDate);
    boolean existsByArticleUuidAndEntitySymbol(String articleUuid, String entitySymbol);
    List<MarketauxEntitySentimentEntity> findAllByOrderByIdAsc();
}
