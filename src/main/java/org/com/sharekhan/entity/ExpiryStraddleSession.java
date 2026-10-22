package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

/** Immutable entry claim: a crash never permits replaying a possibly submitted pair. */
@Entity
@Table(name="expiry_straddle_sessions",indexes=@Index(name="idx_expiry_straddle_day",columnList="trading_day"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpiryStraddleSession {
    @Column(length=64) private String sharedSignalId;
    @Id @Column(length=120) private String id;
    @Column(nullable=false) private Long appUserId;
    @Column(nullable=false) private Long brokerCredentialsId;
    @Column(nullable=false,length=16) private String symbol;
    @Column(nullable=false) private LocalDate tradingDay;
    @Column(nullable=false,length=2) private String slot;
    @Column(nullable=false) private LocalDateTime exitAt;
    @Column(nullable=false) private double strike;
    @Column(nullable=false) private int lots;
    @Column(nullable=false) private long expectedQuantity;
    @Column(nullable=false) private LocalDateTime claimedAt;
    // Failure closes any filled leg early; late fills remain discoverable by the unique source.
    @Column(nullable=false) private boolean abortRequested;
    @Column(precision=19,scale=2) private java.math.BigDecimal profitTargetPerLot;
    @Column(nullable=false) private boolean profitExitRequested;
    @Column(length=64) private String strategyRunId;
    @Column(length=500) private String message;
}
