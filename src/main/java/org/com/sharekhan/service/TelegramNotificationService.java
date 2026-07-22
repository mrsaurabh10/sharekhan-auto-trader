package org.com.sharekhan.service;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
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

    @Autowired
    public TelegramNotificationService(@Value("${app.telegram.bot-token:}") String botToken,
                                       @Value("${app.telegram.chat-id:}") String chatId) {
        this.botToken = botToken == null ? "" : botToken;
        this.chatId = chatId == null ? "" : chatId;
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
        sendTradeMessageForUser(appUserId, title, body, null);
    }

    /**
     * Sends a user-scoped trade alert with an optional inline action button.
     * The callback is handled by the authenticated Telegram webhook flow.
     */
    public void sendTradeMessageForUser(Long appUserId, String title, String body, String callbackData) {
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
        sendTradeMessageWithCallback(title, prefix + (body == null ? "" : body), callbackData);
    }

    private void sendTradeMessageWithCallback(String title, String body, String callbackData) {
        if (botToken.isBlank() || chatId.isBlank()) {
            log.debug("Telegram not configured (botToken/chatId missing) - skipping message");
            return;
        }
        try {
            String uri = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", (title == null ? "" : title) + "\n" + (body == null ? "" : body));
            payload.put("disable_web_page_preview", true);
            if (callbackData != null && !callbackData.isBlank()) {
                payload.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(Map.of(
                        "text", "Disable StockBazaari",
                        "callback_data", callbackData)) )));
            }
            ResponseEntity<String> response = restTemplate.postForEntity(uri, new HttpEntity<>(payload, headers), String.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                log.warn("Telegram sendMessage returned non-2xx: {}", response);
            }
        } catch (Exception e) {
            log.warn("Failed to send Telegram action message: {}", e.getMessage());
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        if (botToken.isBlank() || callbackQueryId == null || callbackQueryId.isBlank()) {
            return;
        }
        try {
            String uri = "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
            Map<String, Object> payload = new HashMap<>();
            payload.put("callback_query_id", callbackQueryId);
            payload.put("text", text == null ? "" : text);
            payload.put("show_alert", true);
            restTemplate.postForEntity(uri, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            log.warn("Failed answering Telegram callback: {}", e.getMessage());
        }
    }
}
