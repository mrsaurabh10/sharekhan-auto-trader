package org.com.sharekhan.auth;

import java.time.*;
import java.util.*;
import org.com.sharekhan.entity.AccessTokenEntity;

/** Shared daily-session rule. Never substitute a customer's session for the global feed. */
public final class ShoonyaSavedSession {
    private ShoonyaSavedSession() { }
    public static Optional<AccessTokenEntity> currentGlobal(List<AccessTokenEntity> rows, LocalDateTime now, long ttl) {
        if (rows == null || ttl <= 60) return Optional.empty();
        var zone = ZoneId.of("Asia/Kolkata");
        return rows.stream().filter(Objects::nonNull)
                .filter(t -> t.getBrokerCredentialsId() == null && t.getUserId() == null)
                .filter(t -> t.getExpiry() != null && t.getToken() != null && !t.getToken().isBlank())
                .filter(t -> {
                    var issued = t.getExpiry().minusSeconds(ttl).atZone(zone);
                    return issued.toLocalDate().equals(now.toLocalDate())
                            && !issued.toLocalDateTime().isAfter(now);
                }).max(Comparator.comparing(AccessTokenEntity::getExpiry));
    }
    public static final class Unavailable extends IllegalStateException {
        public Unavailable() {
            super("No saved current-day global Shoonya session; reconnect Shoonya. Automatic login: 08:40 IST, retries 08:50/08:55");
        }
    }
}
