package org.com.sharekhan.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.Immutable;

/** One immutable opening selection per strategy and trading day. */
@Entity
@Table(name = "mover_selection_snapshot")
@Immutable
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class MoverSelectionSnapshot {
    @Id @Column(length = 100) private String id;
    @Column(nullable = false, length = 80) private String templateId;
    @Column(nullable = false) private LocalDate tradingDay;
    @Column(nullable = false) private LocalDateTime captureStartedAt;
    @Column(nullable = false) private LocalDateTime capturedAt;
    @Column(nullable = false) private int schemaVersion;
    @Column(nullable = false, columnDefinition = "text") private String candidatesJson;
}
