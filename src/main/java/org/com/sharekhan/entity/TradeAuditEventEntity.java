package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Immutable, user-visible history for strategy decisions and trade execution. */
@Entity
@Table(name = "trade_audit_events", indexes = {
        @Index(name = "idx_trade_audit_user_time", columnList = "app_user_id,occurred_at"),
        @Index(name = "idx_trade_audit_request", columnList = "trigger_request_id"),
        @Index(name = "idx_trade_audit_trade", columnList = "trade_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeAuditEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
    @Column(name = "app_user_id")
    private Long appUserId;
    @Column(name = "trigger_request_id")
    private Long triggerRequestId;
    @Column(name = "trade_id")
    private Long tradeId;
    @Column(name = "strategy_id", length = 120)
    private String strategyId;
    @Column(length = 160)
    private String source;
    @Column(length = 80)
    private String symbol;
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;
    @Column(length = 40)
    private String outcome;
    @Column(length = 1200)
    private String reason;
    private String optionType;
    private String expiry;
    private Double strikePrice;
    private Double spotPrice;
    private Double optionLtp;
    private Double bestBid;
    private Double bestAsk;
    @Column(columnDefinition = "TEXT")
    private String details;
}
