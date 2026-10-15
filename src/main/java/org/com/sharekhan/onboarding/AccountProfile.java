package org.com.sharekhan.onboarding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "account_profile")
@Getter @Setter
public class AccountProfile {
    @Id private Long userId;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(nullable = false, length = 100) private String displayName;
    @Column(nullable = false) private Instant createdAt;
    private Instant verifiedAt;
    @Column(length = 64, unique = true) private String tokenHash;
    @Column(length = 16) private String tokenPurpose;
    private Instant tokenExpiresAt;
    @Version private Long version;
}
