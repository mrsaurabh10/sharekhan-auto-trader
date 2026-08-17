package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.MarketauxCollectionRunEntity;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.repository.MarketauxCollectionRunRepository;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Persists the small sentiment subset required for intraday market analysis. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketauxIndiaSentimentCollector {
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private final MarketauxNewsService newsService;
    private final MarketauxCollectionRunRepository collectionRunRepository;
    private final MarketauxEntitySentimentRepository sentimentRepository;

    public void collect(LocalDate tradingDate, int slotNumber, LocalDateTime scheduledAt, int articleLimit) {
        LocalDateTime requestedAt = LocalDateTime.now(INDIA_ZONE);
        MarketauxCollectionRunEntity run;
        try {
            run = collectionRunRepository.saveAndFlush(MarketauxCollectionRunEntity.builder()
                    .tradingDate(tradingDate).slotNumber(slotNumber).scheduledAt(scheduledAt)
                    .requestedAt(requestedAt).success(false).entityCount(0).build());
        } catch (DataIntegrityViolationException ignored) {
            // The unique day/slot constraint prevents duplicate calls after a restart or overlapping scheduler.
            return;
        }

        try {
            List<MarketauxNewsService.IndiaEntitySentiment> sentiments =
                    newsService.latestIndiaEntitySentiments(articleLimit);
            Set<ArticleEntityKey> responseKeys = new HashSet<>();
            List<MarketauxEntitySentimentEntity> rows = sentiments.stream()
                    .filter(sentiment -> StringUtils.hasText(sentiment.articleUuid()))
                    .filter(sentiment -> StringUtils.hasText(sentiment.entitySymbol()))
                    .filter(sentiment -> responseKeys.add(new ArticleEntityKey(
                            sentiment.articleUuid(), sentiment.entitySymbol())))
                    .filter(sentiment -> !sentimentRepository.existsByArticleUuidAndEntitySymbol(
                            sentiment.articleUuid(), sentiment.entitySymbol()))
                    .map(sentiment ->
                    MarketauxEntitySentimentEntity.builder()
                            .collectionRunId(run.getId()).tradingDate(tradingDate)
                            .articleUuid(sentiment.articleUuid()).entitySymbol(sentiment.entitySymbol())
                            .entityName(sentiment.entityName()).sentimentScore(sentiment.sentimentScore())
                            .articlePublishedAt(sentiment.publishedAt() == null ? null : sentiment.publishedAt().toLocalDateTime())
                            .collectedAt(requestedAt).build()).toList();
            sentimentRepository.saveAll(rows);
            run.setSuccess(true);
            run.setEntityCount(rows.size());
            collectionRunRepository.save(run);
            log.info("Stored {} Marketaux India entity sentiment scores for slot {} on {}", rows.size(), slotNumber, tradingDate);
        } catch (Exception ex) {
            run.setErrorMessage(truncate(ex.getMessage()));
            collectionRunRepository.save(run);
            log.warn("Marketaux India collection failed for slot {} on {}: {}", slotNumber, tradingDate, ex.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) return "Unknown error";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private record ArticleEntityKey(String articleUuid, String entitySymbol) {
    }
}
