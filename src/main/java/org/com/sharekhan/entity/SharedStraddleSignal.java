package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;

/** Shared contract selection, independent of any subscription, broker, lots or order outcome. */
@Entity @Table(name="shared_straddle_signals",uniqueConstraints=@UniqueConstraint(name="uq_shared_straddle_session",columnNames={"tradingDay","symbol","slot"}))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class SharedStraddleSignal {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private LocalDate tradingDay;
    @Column(nullable=false,length=16) private String symbol;
    @Column(nullable=false,length=2) private String slot;
    @Column(nullable=false,length=16) private String expiry;
    @Column(nullable=false) private double strike;
    @Column(nullable=false) private Integer ceScripCode;
    @Column(nullable=false) private Integer peScripCode;
    @Column(nullable=false) private int lotSize;
    @Column(nullable=false) private double spotPrice;
    @Column(nullable=false) private LocalDateTime spotObservedAt;
    @Column(nullable=false) private LocalDateTime createdAt;
}
