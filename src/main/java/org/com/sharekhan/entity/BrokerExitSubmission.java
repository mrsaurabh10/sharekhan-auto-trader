package org.com.sharekhan.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Table(name="broker_exit_submissions") @Getter @Setter
public class BrokerExitSubmission {
    @Id private String id;
    private Long tradeId;
    private Long credentialId;
    private int generation;
    private String orderId;
    private boolean confirmedInactive;
    private Instant createdAt;
}
