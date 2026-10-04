package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Completed five-minute spot candles retained across application restarts. */
@Entity
@Table(name = "strategy_candle_history", indexes = {
        @Index(name = "idx_strategy_candle_history_instrument_time", columnList = "instrument_key,candle_time")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyCandleHistoryEntity {
    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "instrument_key", nullable = false, length = 64)
    private String instrumentKey;
    @Column(name = "candle_time", nullable = false)
    private LocalDateTime candleTime;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private double closePrice;
    private Long volume;
}
