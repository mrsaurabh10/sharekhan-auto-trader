package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Durable observations shared by requests explicitly submitted as one time-exit group. */
@Entity @Table(name="trade_time_exit_state") @Getter @Setter @NoArgsConstructor
public class TradeTimeExitState {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private LocalDateTime startedAt;
    private LocalDateTime progressReachedAt;
    private LocalDateTime timedOutAt;
}
