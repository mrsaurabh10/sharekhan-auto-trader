package org.com.sharekhan.onboarding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface AccountProfileRepository extends JpaRepository<AccountProfile, Long> {
    Optional<AccountProfile> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountProfile> findByTokenHash(String tokenHash);
}
