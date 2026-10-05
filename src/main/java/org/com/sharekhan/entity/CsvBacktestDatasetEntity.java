package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
@Entity @Table(name="csv_backtest_datasets") @Data @NoArgsConstructor
public class CsvBacktestDatasetEntity {
    @Id @Column(length=64) private String id;
    private String symbol;
    private String filename;
    private int inputMinutes;
    private long inputRows;
    private long outsideSession;
    private long incompleteGroups;
    private long missingSlots;
    private long normalizedTimestamps;
    private int candleCount;
    private LocalDate firstDate;
    private LocalDate lastDate;
    private LocalDateTime importedAt;
}
