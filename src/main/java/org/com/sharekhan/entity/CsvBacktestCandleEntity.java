package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name="csv_backtest_candles",indexes=@Index(name="idx_csv_dataset_time",columnList="dataset_id,candle_time",unique=true))
@Data @NoArgsConstructor
public class CsvBacktestCandleEntity {
    @Id @Column(length=90) private String id;
    @Column(name="dataset_id",nullable=false,length=64) private String datasetId;
    @Column(name="candle_time",nullable=false) private LocalDateTime candleTime;
    @Column(nullable=false) private double openPrice;
    @Column(nullable=false) private double highPrice;
    @Column(nullable=false) private double lowPrice;
    @Column(nullable=false) private double closePrice;
    @Column(nullable=false) private long volume;
}
