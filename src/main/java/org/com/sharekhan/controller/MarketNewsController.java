package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.service.MarketauxNewsService;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/market-news")
@RequiredArgsConstructor
public class MarketNewsController {

    private final MarketauxNewsService marketauxNewsService;
    private final MarketauxEntitySentimentRepository sentimentRepository;

    /**
     * Fetches English-language Marketaux news explicitly filtered to India. Symbols and entities
     * use Marketaux's comma-separated identifier formats, supplied as repeated query parameters.
     */
    @GetMapping("/india")
    public ResponseEntity<Map<String, Object>> indiaNews(
            @RequestParam(required = false) List<String> symbols,
            @RequestParam(required = false) List<String> entities,
            @RequestParam(name = "publishedAfter", required = false) String publishedAfter,
            @RequestParam(name = "publishedBefore", required = false) String publishedBefore,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "1") int page) {
        try {
            return ResponseEntity.ok(marketauxNewsService.indiaNews(
                    symbols, entities, publishedAfter, publishedBefore, limit, page));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("Unable to fetch India market news: {}", ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /** Returns the latest stored score per entity without consuming another Marketaux request. */
    @GetMapping("/india/sentiments")
    public ResponseEntity<Map<String, Object>> indiaSentiments(
            @RequestParam(required = false) String date) {
        LocalDate tradingDate;
        try {
            tradingDate = date == null || date.isBlank() ? LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) : LocalDate.parse(date);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "date must be an ISO date (YYYY-MM-DD)."));
        }
        try {
            LinkedHashMap<String, Map<String, Object>> latestByEntity = new LinkedHashMap<>();
            for (MarketauxEntitySentimentEntity row : sentimentRepository.findTop500ByTradingDateOrderByCollectedAtDesc(tradingDate)) {
                String entity = row.getEntityName() == null || row.getEntityName().isBlank() ? row.getEntitySymbol() : row.getEntityName();
                String key = row.getEntitySymbol() == null || row.getEntitySymbol().isBlank() ? entity : row.getEntitySymbol();
                if (key != null && !latestByEntity.containsKey(key)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("entity", entity);
                    item.put("symbol", row.getEntitySymbol());
                    item.put("sentimentScore", row.getSentimentScore());
                    item.put("articleTitle", row.getArticleTitle());
                    item.put("articleSource", row.getArticleSource());
                    item.put("articleUrl", row.getArticleUrl());
                    item.put("identifiedEntityCount", row.getIdentifiedEntityCount());
                    item.put("broadMarketArticle", Boolean.TRUE.equals(row.getBroadMarketArticle()));
                    latestByEntity.put(key, item);
                }
            }
            return ResponseEntity.ok(Map.of("tradingDate", tradingDate, "data", List.copyOf(latestByEntity.values())));
        } catch (Exception ex) {
            log.error("Unable to read Marketaux India sentiment rows", ex);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Unable to read stored India sentiment data."));
        }
    }
}
