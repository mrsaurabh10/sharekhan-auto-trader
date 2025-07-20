package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "trigger_trade_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerTradeRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private Integer scripCode;
    private String exchange;

    private String instrumentType;
    private Double strikePrice;
    private String optionType;
    private String expiry;

    private Long quantity; // Final quantity = lots × lotSize

    private Double entryPrice;
    private Double stopLoss;

    private Double target1;
    private Double target2;
    private Double target3;

    private Double trailingSl;

    @Column(name = "intraday")
    private Boolean intraday;  // default false




    @Enumerated(EnumType.STRING)
    private TriggeredTradeStatus status;

    private LocalDateTime createdAt;
}
