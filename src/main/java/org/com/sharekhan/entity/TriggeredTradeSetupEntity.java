package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "triggered_trade_setups")
@EntityListeners(TradeCostPersistenceListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggeredTradeSetupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_request_id")
    private Long triggerRequestId;

    private String symbol;
    private Integer scripCode;

    // reference to broker_credentials id
    private Long brokerCredentialsId;

    // application user id
    private Long appUserId;

    private String exchange;
    private String instrumentType;
    private Double strikePrice;
    private String optionType;
    private String expiry;

    // Store final executed quantity as Long (number of shares) to avoid unit confusion with lots
    private Long quantity;
    
    private Integer lots; // Current number of lots
    
    private Integer originalLots; // Original number of lots for partial booking calculations

    // Identifies the legs created together for a multi-target option trade.
    private Long targetOrderGroupId;
    private Integer targetStage;

    private Double entryPrice;
    
    // Actual entry price of the option/instrument (especially useful when entry is based on spot)
    private Double actualEntryPrice;

    private Double stopLoss;

    private Double target1;
    private Double target2;
    private Double target3;

    private Double trailingSl;
    
    private Boolean tslEnabled; // Flag to enable TSL logic
    
    private Boolean useSpotPrice; // Legacy flag for backward compatibility
    
    // Granular spot price usage flags
    private Boolean useSpotForEntry;
    private Boolean useSpotForSl;
    private Boolean useSpotForTarget;

    private Integer spotScripCode; // Scrip code of the underlying spot

    @Column(name = "order_id",unique = true)
    private String orderId;

    @Column(name = "exit_order_id",unique = true)
    private String exitOrderId;

    private String exitReason;

    /** Short, machine-readable outcome code or broker rejection reason. */
    @Column(name = "reason", length = 255)
    private String reason;

    /** Human-readable context that explains the outcome. */
    @Column(name = "comment", length = 2000)
    private String comment;

    @Column(name = "intraday")
    private Boolean intraday;  // default false

    @Column(name = "source")
    private String source;

    private Boolean gapProtectionEnabled;
    private Double gapDayOpen;
    private Double gapPreviousClose;
    private Double gapStopLoss;
    private Integer gapReentryCount;

    @Enumerated(EnumType.STRING)
    private TriggeredTradeStatus status;

    private LocalDateTime triggeredAt;

    // Time when the entry order was actually executed (avgPrice observed)
    private LocalDateTime entryAt;

    private LocalDateTime exitedAt;

    private Double exitPrice;
    private Double pnl;

    @Column(name = "trade_cost")
    private Double tradeCost;

    @Column(name = "effective_pnl")
    private Double effectivePnl;

    private LocalDateTime exitOrderPlacedAt;

    /**
     * Timestamp of the atomic transition into EXIT_TRIGGERED.  This is distinct
     * from exitOrderPlacedAt: an exit worker may need a short time to create the
     * broker order after it has claimed the trade.
     */
    @Column(name = "exit_claimed_at")
    private LocalDateTime exitClaimedAt;
}
