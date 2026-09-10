package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketaux_entity_sentiments", indexes = {
        @Index(name = "idx_marketaux_sentiment_day_symbol", columnList = "trading_date,entity_symbol"),
        @Index(name = "idx_marketaux_sentiment_collected", columnList = "collected_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MarketauxEntitySentimentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "collection_run_id", nullable = false)
    private Long collectionRunId;
    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(name = "article_uuid", length = 64)
    private String articleUuid;
    @Column(name = "entity_symbol", length = 100)
    private String entitySymbol;
    @Column(name = "entity_name", length = 300)
    private String entityName;
    @Column(name = "sentiment_score", nullable = false)
    private Double sentimentScore;
    @Column(name = "article_published_at")
    private LocalDateTime articlePublishedAt;
    @Column(name = "article_title", length = 1000)
    private String articleTitle;
    @Column(name = "article_source", length = 300)
    private String articleSource;
    @Column(name = "article_url", length = 2000)
    private String articleUrl;
    // Null is retained for rows stored before article metadata collection was introduced.
    @Column(name = "identified_entity_count")
    private Integer identifiedEntityCount;
    @Column(name = "broad_market_article")
    private Boolean broadMarketArticle;
    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
