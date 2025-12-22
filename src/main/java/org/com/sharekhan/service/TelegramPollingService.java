package org.com.sharekhan.service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.*;
import java.util.*;

@Service
public class TelegramPollingService {

    private static final String BOT_TOKEN = "8330200742:AAEinZnYlgatRTRcLrPH7r_MWIvJ0MBX_wY";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates";

    // trading hours
    private static final LocalTime START_TIME = LocalTime.of(9, 15);
    private static final LocalTime END_TIME = LocalTime.of(23, 30);

    // India timezone
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private long lastUpdateId = 0;

    private final TradingMessageService tradingMessageService;

    public TelegramPollingService(TradingMessageService tradingMessageService) {
        this.tradingMessageService = tradingMessageService;
    }

    @PostConstruct
    public void init() {
        System.out.println("Telegram polling initialized ✳️");
    }

    // Check every 8 seconds
    @Scheduled(fixedDelay = 8000)
    public void monitorTelegramUpdates() {
        LocalDateTime now = LocalDateTime.now(INDIA_ZONE);
        LocalTime currentTime = now.toLocalTime();
        DayOfWeek day = now.getDayOfWeek();

        // Restrict to weekdays only (Mon–Fri)
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return;
        }

        // Restrict to trading hours only
        if (currentTime.isBefore(START_TIME) || currentTime.isAfter(END_TIME)) {
            return;
        }

        try {
            String url = API_URL + "?timeout=10&offset=" + (lastUpdateId + 1);
            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getBody() == null) return;

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("result");
            if (results == null || results.isEmpty()) return;

            for (Map<String, Object> update : results) {
                Map<String, Object> message = (Map<String, Object>) update.get("message");
                long thisUpdateId = ((Number) update.get("update_id")).longValue();
                if (message == null) {
                    lastUpdateId = thisUpdateId;
                    continue;
                }

                String text = (String) message.get("text");
                if (text == null || text.isBlank()) {
                    lastUpdateId = thisUpdateId;
                    continue;
                }

                String sender = extractSenderName(message);

                // Construct a unique id for dedupe: telegram:<chatId>:<messageId>
                String uniqueId = null;
                try {
                    Map<String, Object> chat = (Map<String, Object>) message.get("chat");
                    Object chatId = chat != null ? chat.get("id") : null;
                    Object messageId = message.get("message_id");
                    if (chatId != null && messageId != null) {
                        uniqueId = "telegram:" + chatId.toString() + ":" + messageId.toString();
                    }
                } catch (Exception ignored) {}

                // Whether it's Telegram or forwarded WhatsApp text — pass uniqueId for dedupe
                tradingMessageService.handleRawMessage(text, sender, uniqueId);
                lastUpdateId = thisUpdateId;
             }

        } catch (Exception e) {
            System.err.println("Polling error: " + e.getMessage());
        }
    }

    private String extractSenderName(Map<String, Object> message) {
        try {
            Map<String, Object> chat = (Map<String, Object>) message.get("chat");
            return (String) chat.getOrDefault("title", "telegram");
        } catch (Exception e) {
            return "telegram";
        }
    }
}
