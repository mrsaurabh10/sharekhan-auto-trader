package org.com.sharekhan.telegram;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface TelegramUserSettingsRepository extends JpaRepository<TelegramUserSettings, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TelegramUserSettings s where s.appUserId = :userId")
    Optional<TelegramUserSettings> lockByUserId(@Param("userId") Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TelegramUserSettings s where s.linkHash = :hash")
    Optional<TelegramUserSettings> lockByLinkHash(@Param("hash") String hash);
}
