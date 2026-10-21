package org.com.sharekhan.telegram;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "telegram_user_settings")
@Data
@NoArgsConstructor
public class TelegramUserSettings {
    @Id private Long appUserId;
    @Column(length = 32) private String username;
    @Column(length = 20) private String chatId;
    @Column(length = 64, unique = true) private String linkHash;
    private java.time.Instant linkExpiresAt;
}
