package org.com.sharekhan.service;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple Telegram notification helper used by the trading services to send concise alerts.
 * Token and chatId can be provided via environment or application properties and wired by Spring.
 * This class is intentionally tiny and defensive: if token/chatId are empty it becomes a no-op.
 */
@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final String botToken;
    private final String chatId;
    private final RestTemplate restTemplate;

    @Autowired(required = false)
    private AppUserRepository appUserRepository;

    public TelegramNotificationService() {
        // Try to read from environment variables for easy runtime configuration
        this.botToken = System.getenv().getOrDefault("TELEGRAM_BOT_TOKEN", "8330200742:AAEinZnYlgatRTRcLrPH7r_MWIvJ0MBX_wY");
        this.chatId = System.getenv().getOrDefault("TELEGRAM_CHAT_ID", "376501162");
        this.restTemplate = new RestTemplate();
    }

    // For tests or direct wiring
    public TelegramNotificationService(String botToken, String chatId, RestTemplate restTemplate) {
        this.botToken = botToken == null ? "" : botToken;
        this.chatId = chatId == null ? "" : chatId;
        this.restTemplate = restTemplate == null ? new RestTemplate() : restTemplate;
    }

    public void sendTradeMessage(String title, String body) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            // Not configured - no-op
            log.debug("Telegram not configured (botToken/chatId missing) - skipping message");
            return;
        }
        try {
            String text = (title == null ? "" : title) + "\n" + (body == null ? "" : body);

            // Build JSON payload and POST - avoids URL-encoding issues seen with GET+URLEncoder
            String uri = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON_UTF8);

            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            // disable web page preview and keep message simple; you can add parse_mode if needed
            payload.put("disable_web_page_preview", true);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
            // Fire and forget - but still catch exceptions
            ResponseEntity<String> resp = restTemplate.postForEntity(uri, req, String.class);
            if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                log.warn("Telegram sendMessage returned non-2xx: {}", resp);
            }
        } catch (Exception e) {
            // Log but do not fail trading flow
            log.warn("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    /**
     * Convenience: prepend the AppUser's username (when available) to the body before sending.
     */
    public void sendTradeMessageForUser(Long appUserId, String title, String body) {
        String prefix = "";
        try {
            if (appUserId != null && appUserRepository != null) {
                String username = appUserRepository.findById(appUserId)
                        .map(AppUser::getUsername)
                        .orElse("user-" + appUserId);
                prefix = "User: " + username + " (#" + appUserId + ")\n";
            } else if (appUserId != null) {
                prefix = "UserId: #" + appUserId + "\n";
            }
        } catch (Exception e) {
            // ignore lookup failures and send without username
            prefix = (appUserId != null) ? ("UserId: #" + appUserId + "\n") : "";
        }
        sendTradeMessage(title, prefix + (body == null ? "" : body));
    }
}
