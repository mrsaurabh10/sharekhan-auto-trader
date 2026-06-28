package org.com.sharekhan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "backtest_replay_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestReplayEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    @Column(name = "trade_setup_id", nullable = false)
    private Long tradeSetupId;

    @Column(name = "event_index", nullable = false)
    private Integer eventIndex;

    @Column(name = "event_at")
    private LocalDateTime eventAt;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "reason")
    private String reason;

    @Column(name = "candle_interval")
    private String interval;

    @Column(name = "trigger_price_policy")
    private String triggerPricePolicy;

    @Column(name = "square_off_time")
    private String squareOffTime;

    @Column(name = "price_source")
    private String priceSource;

    @Column(name = "reference_price")
    private Double referencePrice;

    @Column(name = "option_price")
    private Double optionPrice;

    @Column(name = "stop_loss")
    private Double stopLoss;

    @Column(name = "target")
    private Double target;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "lots")
    private Integer lots;

    @Column(name = "pnl")
    private Double pnl;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;
}
