package org.com.sharekhan.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Simple Telegram notification helper used by the trading services to send concise alerts.
 * Token and chatId can be provided via environment or application properties and wired by Spring.
 * This class is intentionally tiny and defensive: if token/chatId are empty it becomes a no-op.
 */
@Service
public class TelegramNotificationService {

    private final String botToken;
    private final String chatId;
    private final RestTemplate restTemplate;

    public TelegramNotificationService() {
        // Try to read from environment variables for easy runtime configuration
        this.botToken = System.getenv().getOrDefault("TELEGRAM_BOT_TOKEN", "");
        this.chatId = System.getenv().getOrDefault("TELEGRAM_CHAT_ID", "");
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
            return;
        }
        try {
            String text = title + "\n" + body;
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String uri = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + encoded;
            // fire-and-forget; don't keep the response in a local var to avoid unused warnings
            restTemplate.getForEntity(uri, String.class);
        } catch (Exception e) {
            // Log but do not fail trading flow
            System.err.println("Failed to send Telegram message: " + e.getMessage());
        }
    }
}
