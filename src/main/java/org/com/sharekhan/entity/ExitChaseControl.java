package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Separate from trade snapshots so polling cannot reset chase budgets or alert delivery. */
@Entity @Table(name="exit_chase_control") @Getter @Setter
public class ExitChaseControl {
    @Id private Long tradeId;
    private String orderId;
    private String intent;
    private Instant startedAt;
    private boolean active;
    @Column(length=2000) private String attention;
    private int notificationAttempts;
    private Instant nextNotificationAt;
    private boolean notificationDelivered;
    @Version private Long version;
}
