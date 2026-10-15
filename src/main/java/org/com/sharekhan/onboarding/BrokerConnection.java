package org.com.sharekhan.onboarding;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Table(name="broker_connection") @Getter @Setter
public class BrokerConnection {
    @Id private Long credentialId;
    private Instant verifiedUntil;
    @Version private Long version;
}
