package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;

@Entity @Table(name="strategy_runs",indexes={@Index(name="idx_strategy_run_owner_time",columnList="app_user_id,created_at"),@Index(name="idx_strategy_run_status",columnList="status")})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class StrategyRun {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private Long appUserId;
    @Column(nullable=false) private Long brokerCredentialsId;
    private Long subscriptionId;
    @Column(nullable=false,length=100) private String templateId;
    @Column(nullable=false) private String symbol;
    @Column(nullable=false) private LocalDate tradingDay;
    @Column(nullable=false,length=16) private String instance;
    private Integer lots;
    @Embedded private StrategyRiskSettings riskSettings;
    @Column(nullable=false,length=16) private String status;
    @Column(nullable=false) private LocalDateTime createdAt;
    private LocalDateTime closeRequestedAt;
    private LocalDateTime closedAt;
    @Column(length=80) private String exitReason;
    @Column(precision=19,scale=2) private BigDecimal peakPnl;
    @Column(precision=19,scale=2) private BigDecimal stopFloor;
    @Column(length=500) private String message;
}
