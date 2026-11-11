package org.com.sharekhan.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Durable signal-level observations only; orders and fills remain in the normal engine. */
@Entity @Table(name="adx_dmi_exit_state") @Getter @Setter @NoArgsConstructor
public class AdxDmiExitState {
    @Id @Column(length=64) private String id;
    @Column(nullable=false) private LocalDateTime startedAt;
    private LocalDateTime t1ReachedAt;
    private LocalDateTime timedOutAt;
}
