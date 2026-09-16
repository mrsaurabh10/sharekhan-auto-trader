package org.com.sharekhan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.MarketauxProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side Marketaux client for Indian-market news. The provider token is deliberately
 * never sent to browsers or emitted in logs.
 */
@Slf4j
@Service
public class MarketauxNewsService {

    private static final String INDIA_COUNTRY_CODE = "in";
    private static final int MAX_LIMIT = 50;

    private final MarketauxProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public MarketauxNewsService(MarketauxProperties properties, ObjectMapper objectMapper) {
        this(properties, restTemplate(properties), objectMapper);
    }

    MarketauxNewsService(MarketauxProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    private static RestTemplate restTemplate(MarketauxProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(requestFactory);
    }

    public Map<String, Object> indiaNews(List<String> symbols, List<String> entities, String publishedAfter,
                                         String publishedBefore, int limit, int page) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Marketaux news integration is disabled.");
        }
        if (!StringUtils.hasText(properties.getApiToken())) {
            throw new IllegalStateException("Marketaux API token is not configured. Set MARKETAUX_API_TOKEN.");
        }

        String url = buildUrl(symbols, entities, publishedAfter, publishedBefore, limit, page);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
            return normalizeResponse(response.getBody());
        } catch (HttpStatusCodeException ex) {
            log.warn("Marketaux India news request failed with HTTP {}", ex.getStatusCode());
            throw new IllegalStateException("Marketaux request failed with HTTP " + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            log.warn("Marketaux India news request could not be completed: {}", ex.getMessage());
            throw new IllegalStateException("Marketaux request could not be completed.");
        }
    }

    /** Fetches a provider page and retains only the article/entity identifiers and sentiment needed for intraday storage. */
    public List<IndiaEntitySentiment> latestIndiaEntitySentiments(int limit) {
        Map<String, Object> response = indiaNews(null, null, null, null, limit, 1);
        Object data = response.get("data");
        if (!(data instanceof List<?> articles)) {
            return List.of();
        }
        return articles.stream()
                .filter(Map.class::isInstance)
                .flatMap(article -> sentimentsFromArticle((Map<?, ?>) article).stream())
                .toList();
    }

    private List<IndiaEntitySentiment> sentimentsFromArticle(Map<?, ?> article) {
        String articleUuid = text(article.get("uuid"));
        OffsetDateTime publishedAt = parsePublishedAt(text(article.get("published_at")));
        String articleTitle = text(article.get("title"));
        String articleSource = text(article.get("source"));
        String articleUrl = text(article.get("url"));
        Object entities = article.get("entities");
        if (!(entities instanceof List<?> entityList)) {
            return List.of();
        }
        int identifiedEntityCount = (int) entityList.stream().filter(Map.class::isInstance).count();
        boolean broadMarketArticle = identifiedEntityCount > 3;
        return entityList.stream().filter(Map.class::isInstance).map(entity -> {
            Map<?, ?> entityMap = (Map<?, ?>) entity;
            Object score = entityMap.get("sentiment_score");
            return new IndiaEntitySentiment(articleUuid, text(entityMap.get("symbol")), text(entityMap.get("name")),
                    score instanceof Number number ? number.doubleValue() : null, publishedAt,
                    articleTitle, articleSource, articleUrl, identifiedEntityCount, broadMarketArticle);
        }).filter(sentiment -> sentiment.sentimentScore() != null).toList();
    }

    private OffsetDateTime parsePublishedAt(String value) {
        try {
            return StringUtils.hasText(value) ? OffsetDateTime.parse(value) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String buildUrl(List<String> symbols, List<String> entities, String publishedAfter, String publishedBefore,
                            int limit, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(stripTrailingSlash(properties.getBaseUrl()) + "/news/all")
                .queryParam("api_token", properties.getApiToken())
                .queryParam("countries", INDIA_COUNTRY_CODE)
                .queryParam("language", "en")
                .queryParam("filter_entities", true)
                .queryParam("limit", Math.min(Math.max(limit, 1), MAX_LIMIT))
                .queryParam("page", Math.max(page, 1));
        addCsvQueryParam(builder, "symbols", symbols);
        addCsvQueryParam(builder, "entities", entities);
        addDateQueryParam(builder, "published_after", publishedAfter);
        addDateQueryParam(builder, "published_before", publishedBefore);
        return builder.build().encode().toUriString();
    }

    private Map<String, Object> normalizeResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("country", "IN");
            response.put("meta", objectMapper.convertValue(root.path("meta"), Map.class));
            response.put("data", objectMapper.convertValue(root.path("data"), List.class));
            return response;
        } catch (Exception ex) {
            log.warn("Marketaux returned an invalid India news response", ex);
            throw new IllegalStateException("Marketaux returned an invalid response.");
        }
    }

    private void addCsvQueryParam(UriComponentsBuilder builder, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String joined = values.stream().filter(StringUtils::hasText).map(String::trim)
                .filter(value -> !value.isEmpty()).reduce((left, right) -> left + "," + right).orElse("");
        if (StringUtils.hasText(joined)) {
            builder.queryParam(name, joined);
        }
    }

    private void addDateQueryParam(UriComponentsBuilder builder, String name, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            builder.queryParam(name, LocalDate.parse(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException(name + " must be an ISO date (YYYY-MM-DD).");
        }
    }

    private String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Marketaux base URL is not configured.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record IndiaEntitySentiment(String articleUuid, String entitySymbol, String entityName,
                                       Double sentimentScore, OffsetDateTime publishedAt,
                                       String articleTitle, String articleSource, String articleUrl,
                                       int identifiedEntityCount, boolean broadMarketArticle) {
        public IndiaEntitySentiment withEntitySymbol(String canonicalSymbol) {
            return new IndiaEntitySentiment(articleUuid, canonicalSymbol, entityName, sentimentScore, publishedAt,
                    articleTitle, articleSource, articleUrl, identifiedEntityCount, broadMarketArticle);
        }
    }
}
