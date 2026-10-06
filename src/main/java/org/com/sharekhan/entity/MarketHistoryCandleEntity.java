package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Permanent five-minute index archive, separate from the rolling indicator cache. */
@Entity
@Table(name = "market_history_candles", uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_history_symbol_time", columnNames = {"symbol", "candle_time"})
}, indexes = {
        @Index(name = "idx_market_history_symbol_time", columnList = "symbol,candle_time")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MarketHistoryCandleEntity {
    @Id @Column(length = 80) private String id;
    @Column(nullable = false, length = 16) private String symbol;
    @Column(name = "candle_time", nullable = false) private LocalDateTime candleTime;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private double closePrice;
    private Long volume;
    @Column(length = 32) private String provider;
    private LocalDateTime capturedAt;
}
