package org.com.sharekhan.service;

import org.com.sharekhan.entity.MarketauxCollectionRunEntity;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.repository.MarketauxCollectionRunRepository;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketauxIndiaSentimentCollectorTest {

    @Test
    void storesOnlyNewUniqueArticleEntityPairs() {
        MarketauxNewsService newsService = mock(MarketauxNewsService.class);
        MarketauxCollectionRunRepository runRepository = mock(MarketauxCollectionRunRepository.class);
        MarketauxEntitySentimentRepository sentimentRepository = mock(MarketauxEntitySentimentRepository.class);
        when(runRepository.saveAndFlush(any())).thenReturn(MarketauxCollectionRunEntity.builder().id(7L).build());
        when(newsService.latestIndiaEntitySentiments(20)).thenReturn(List.of(
                sentiment("article-1", "BHARTIARTL.NS"),
                sentiment("article-1", "BHARTIARTL.NS"),
                sentiment("article-2", "^NSEI")));
        when(sentimentRepository.existsByArticleUuidAndEntitySymbol("article-1", "BHARTIARTL.NS")).thenReturn(false);
        when(sentimentRepository.existsByArticleUuidAndEntitySymbol("article-2", "^NSEI")).thenReturn(true);

        new MarketauxIndiaSentimentCollector(newsService, runRepository, sentimentRepository)
                .collect(LocalDate.of(2026, 8, 17), 0, LocalDateTime.of(2026, 8, 17, 9, 20), 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketauxEntitySentimentEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(sentimentRepository).saveAll(rows.capture());
        assertEquals(1, rows.getValue().size());
        assertEquals("article-1", rows.getValue().get(0).getArticleUuid());
        assertEquals("BHARTIARTL.NS", rows.getValue().get(0).getEntitySymbol());
        verify(sentimentRepository, never()).existsByArticleUuidAndEntitySymbol(eq("article-1"), eq("^NSEI"));
    }

    private MarketauxNewsService.IndiaEntitySentiment sentiment(String articleUuid, String symbol) {
        return new MarketauxNewsService.IndiaEntitySentiment(
                articleUuid, symbol, symbol, 0.2, null,
                "Article title", "example.com", "https://example.com/article", 1, false);
    }
}
