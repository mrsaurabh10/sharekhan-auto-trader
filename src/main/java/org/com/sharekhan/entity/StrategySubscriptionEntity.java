package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_subscriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategySubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String templateId;

    @Column(nullable = false)
    private String symbol;

    private Integer lots;
    private Boolean intraday;
    private Long appUserId;
    private Long brokerCredentialsId;
    private String source;

    @Column(nullable = false)
    private String status;

    private String lastMessage;
    private String lastEvaluationStatus;
    private Long generatedTradeRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastEvaluatedAt;
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
