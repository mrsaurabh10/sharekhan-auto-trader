package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;
@Entity @Table(name="batman_baskets",indexes=@Index(name="idx_batman_run",columnList="strategy_run_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BatmanBasket {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private Long appUserId;
    @Column(nullable=false) private Long brokerCredentialsId;
    @Column(nullable=false,length=32) private String symbol;
    @Column(nullable=false) private LocalDate expiry;
    @Column(nullable=false,length=64) private String strategyRunId;
    @Column(nullable=false) private LocalDateTime claimedAt;
    @Column(nullable=false) private int baseLots;
    @Column(nullable=false) private int lotSize;
    @Column(nullable=false,length=16000) private String planJson;
    @Column(precision=19,scale=2) private BigDecimal centerCredit;
    @Column(nullable=false,length=20) private String state;
    @Column(length=500) private String message;
}
