package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes historical duplicate provider rows before creating the database uniqueness guard. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketauxSentimentDeduplicationService {
    private final MarketauxEntitySentimentRepository sentimentRepository;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanUpAndProtect() {
        Set<ArticleEntityKey> retainedKeys = new HashSet<>();
        List<MarketauxEntitySentimentEntity> duplicates = new ArrayList<>();
        for (MarketauxEntitySentimentEntity row : sentimentRepository.findAllByOrderByIdAsc()) {
            if (row.getArticleUuid() == null || row.getArticleUuid().isBlank()
                    || row.getEntitySymbol() == null || row.getEntitySymbol().isBlank()) {
                continue;
            }
            if (!retainedKeys.add(new ArticleEntityKey(row.getArticleUuid(), row.getEntitySymbol()))) {
                duplicates.add(row);
            }
        }
        if (!duplicates.isEmpty()) {
            sentimentRepository.deleteAllInBatch(duplicates);
            log.info("Removed {} duplicate Marketaux article/entity sentiment rows", duplicates.size());
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_marketaux_sentiment_article_entity "
                + "ON marketaux_entity_sentiments (article_uuid, entity_symbol)");
    }

    private record ArticleEntityKey(String articleUuid, String entitySymbol) {
    }
}
