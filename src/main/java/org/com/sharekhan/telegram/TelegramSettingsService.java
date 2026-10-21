package org.com.sharekhan.telegram;

import org.com.sharekhan.service.TelegramNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.springframework.http.HttpStatus.*;

@Service
public class TelegramSettingsService {
    private final TelegramUserSettingsRepository settings;
    private final TelegramNotificationService telegram;
    private final boolean authenticatedWebhook;

    public TelegramSettingsService(TelegramUserSettingsRepository settings, TelegramNotificationService telegram,
            @Value("${app.telegram.webhook-secret:}") String secret) {
        this.settings = settings;
        this.telegram = telegram;
        this.authenticatedWebhook = secret != null && !secret.isBlank();
    }

    public record Status(String username, String chatId, boolean connected, boolean botConfigured, String botUsername) {}
    public record Request(String username) {}
    public record Link(String url, Instant expiresAt) {}

    public Status get(Long userId) {
        return status(settings.findById(userId).orElse(new TelegramUserSettings()));
    }

    @Transactional
    public Status save(Long userId, Request request) {
        String username = request == null || request.username() == null ? "" : request.username().trim().replaceFirst("^@", "");
        if (!username.matches("[A-Za-z][A-Za-z0-9_]{4,31}"))
            throw new ResponseStatusException(BAD_REQUEST, "Enter your personal Telegram username, such as vaibhavhubli");
        TelegramUserSettings row = settings.lockByUserId(userId).orElseGet(TelegramUserSettings::new);
        row.setAppUserId(userId);
        if (!username.equalsIgnoreCase(row.getUsername())) {
            row.setChatId(null);
            row.setLinkHash(null);
            row.setLinkExpiresAt(null);
        }
        row.setUsername(username.toLowerCase(java.util.Locale.ROOT));
        return status(settings.saveAndFlush(row));
    }

    @Transactional
    public Link link(Long userId) {
        String bot = telegram.botUsername();
        if (!authenticatedWebhook || bot == null)
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Secure Telegram connection is unavailable. Please try again later.");
        TelegramUserSettings row = settings.lockByUserId(userId).orElseThrow(() ->
                new ResponseStatusException(BAD_REQUEST, "Save your Telegram username first"));
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        row.setLinkHash(hash(token));
        row.setLinkExpiresAt(Instant.now().plusSeconds(600));
        settings.saveAndFlush(row);
        return new Link("https://t.me/" + bot + "?start=connect_" + token, row.getLinkExpiresAt());
    }

    /** Consume only authenticated, direct user messages. Link commands never enter the trading parser. */
    @Transactional
    public boolean handleConnectionMessage(Map<String, Object> message) {
        if (!(message.get("text") instanceof String text) || !text.matches("/start(?:@[A-Za-z0-9_]+)?\\s+connect_.*")) return false;
        if (!authenticatedWebhook) return true;
        String token = text.substring(text.indexOf("connect_") + 8).trim();
        if (!token.matches("[A-Za-z0-9_-]{32}")) return true;
        if (!(message.get("chat") instanceof Map<?, ?> chat) || !"private".equals(chat.get("type"))
                || !(message.get("from") instanceof Map<?, ?> from) || Boolean.TRUE.equals(from.get("is_bot"))) return true;
        String id = String.valueOf(chat.get("id"));
        if (!id.matches("[1-9][0-9]{0,15}") || !id.equals(String.valueOf(from.get("id")))) return true;
        settings.lockByLinkHash(hash(token)).ifPresent(row -> {
            if (row.getLinkExpiresAt() == null || !row.getLinkExpiresAt().isAfter(Instant.now())
                    || !(from.get("username") instanceof String username) || !username.equalsIgnoreCase(row.getUsername())) return;
            row.setChatId(id);
            row.setLinkHash(null);
            row.setLinkExpiresAt(null);
            settings.saveAndFlush(row);
        });
        return true;
    }

    @Transactional
    public void remove(Long userId) {
        settings.lockByUserId(userId).ifPresent(settings::delete);
    }

    private Status status(TelegramUserSettings row) {
        return new Status(row.getUsername(), row.getChatId(), row.getChatId() != null,
                telegram.isBotConfigured() && authenticatedWebhook, telegram.botUsername());
    }

    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
