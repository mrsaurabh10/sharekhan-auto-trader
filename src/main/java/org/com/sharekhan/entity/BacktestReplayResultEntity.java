package org.com.sharekhan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "backtest_replay_results",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_backtest_replay_result_policy",
                columnNames = {"trade_setup_id", "candle_interval", "trigger_price_policy", "square_off_time"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestReplayResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_setup_id", nullable = false)
    private Long tradeSetupId;

    @Column(name = "app_user_id")
    private Long appUserId;

    @Column(name = "broker_credentials_id")
    private Long brokerCredentialsId;

    @Column(name = "broker_name")
    private String brokerName;

    @Column(name = "simulator")
    private Boolean simulator;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "source")
    private String source;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "scrip_code")
    private Integer scripCode;

    @Column(name = "option_type")
    private String optionType;

    @Column(name = "strike_price")
    private Double strikePrice;

    @Column(name = "expiry")
    private String expiry;

    @Column(name = "candle_interval", nullable = false)
    private String interval;

    @Column(name = "trigger_price_policy", nullable = false)
    private String triggerPricePolicy;

    @Column(name = "square_off_time", nullable = false)
    private String squareOffTime;

    @Column(name = "intraday_only")
    private Boolean intradayOnly;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "backtest_entry_at")
    private LocalDateTime backtestEntryAt;

    @Column(name = "backtest_exit_at")
    private LocalDateTime backtestExitAt;

    @Column(name = "backtest_exit_price")
    private Double backtestExitPrice;

    @Column(name = "backtest_exit_reason")
    private String backtestExitReason;

    @Column(name = "backtest_pnl")
    private Double backtestPnl;

    @Column(name = "backtest_exit_count")
    private Integer backtestExitCount;

    @Column(name = "actual_entry_at")
    private LocalDateTime actualEntryAt;

    @Column(name = "actual_exit_at")
    private LocalDateTime actualExitAt;

    @Column(name = "actual_exit_price")
    private Double actualExitPrice;

    @Column(name = "actual_exit_reason")
    private String actualExitReason;

    @Column(name = "actual_pnl")
    private Double actualPnl;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
