package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "triggered_trade_setups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggeredTradeSetupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private Integer scripCode;
    private Long customerId;

    private String exchange;
    private String instrumentType;
    private Double strikePrice;
    private String optionType;
    private String expiry;

    // Store final executed quantity as Long (number of shares) to avoid unit confusion with lots
    private Long quantity;

    private Double entryPrice;
    private Double stopLoss;

    private Double target1;
    private Double target2;
    private Double target3;

    private Double trailingSl;

    @Column(name = "order_id",unique = true)
    private String orderId;

    @Column(name = "exit_order_id",unique = true)
    private String exitOrderId;

    private String exitReason;

    @Column(name = "intraday")
    private Boolean intraday;  // default false


    @Enumerated(EnumType.STRING)
    private TriggeredTradeStatus status;

    private LocalDateTime triggeredAt;
    private LocalDateTime exitedAt;

    private Double exitPrice;
    private Double pnl;
}
