package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketaux_collection_runs", uniqueConstraints =
        @UniqueConstraint(name = "uk_marketaux_run_day_slot", columnNames = {"trading_date", "slot_number"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MarketauxCollectionRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;
    @Column(name = "slot_number", nullable = false)
    private Integer slotNumber;
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;
    @Column(nullable = false)
    private boolean success;
    @Column(name = "entity_count", nullable = false)
    private Integer entityCount;
    @Column(length = 300)
    private String errorMessage;
}
