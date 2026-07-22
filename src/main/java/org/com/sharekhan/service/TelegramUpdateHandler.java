package org.com.sharekhan.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private final TradingMessageService tradingMessageService;
    private final UserConfigService userConfigService;
    private final TelegramNotificationService telegramNotificationService;
    private final String authorizedChatId;

    public TelegramUpdateHandler(TradingMessageService tradingMessageService,
                                 UserConfigService userConfigService,
                                 TelegramNotificationService telegramNotificationService,
                                 @Value("${app.telegram.chat-id:}") String authorizedChatId) {
        this.tradingMessageService = tradingMessageService;
        this.userConfigService = userConfigService;
        this.telegramNotificationService = telegramNotificationService;
        this.authorizedChatId = authorizedChatId == null ? "" : authorizedChatId.trim();
    }

    /**
     * Handle a raw Telegram update payload and forward actionable messages to the trading message pipeline.
     */
    @SuppressWarnings("unchecked")
    public void handleUpdate(Map<String, Object> update) {
        if (update == null || update.isEmpty()) {
            log.debug("Skipping empty Telegram update");
            return;
        }

        if (handleSourceControlCallback(update)) {
            return;
        }

        Map<String, Object> message = extractMessage(update);
        if (message == null || message.isEmpty()) {
            log.debug("Telegram update {} does not contain a primary message object", update.get("update_id"));
            return;
        }

        // Ignore messages sent by bots (commonly echoes of our own bot)
        if (isFromBot(message)) {
            log.debug("Ignoring message {} because sender is a bot", message.get("message_id"));
            return;
        }

        String text = extractMessageText(message);
        if (text == null || text.isBlank()) {
            log.debug("Telegram message {} has no usable text payload", message.get("message_id"));
            return;
        }

        String sender = extractSenderName(message);
        String uniqueId = buildUniqueId(update, message);

        tradingMessageService.handleRawMessage(text, sender, uniqueId);
    }

    @SuppressWarnings("unchecked")
    private boolean handleSourceControlCallback(Map<String, Object> update) {
        Object callbackObject = update.get("callback_query");
        if (!(callbackObject instanceof Map<?, ?> rawCallback)) {
            return false;
        }
        Map<String, Object> callback = (Map<String, Object>) rawCallback;
        Object data = callback.get("data");
        if (!(data instanceof String callbackData) || !callbackData.startsWith("disable-stockbazaari:")) {
            return false;
        }

        String callbackId = stringOrNull(callback.get("id"));
        Long appUserId = parseUserId(callbackData.substring("disable-stockbazaari:".length()));
        if (!isAuthorizedCallbackChat(callback)) {
            log.warn("Rejected StockBazaari disable callback from an unauthorized Telegram chat");
            telegramNotificationService.answerCallbackQuery(callbackId, "This action is not allowed in this chat.");
            return true;
        }
        if (appUserId == null) {
            telegramNotificationService.answerCallbackQuery(callbackId, "Invalid user selection.");
            return true;
        }

        // Keep the value true so an administrator can later re-enable the same config in the dashboard.
        userConfigService.setConfig(appUserId, "StockBazaari", "true", false);
        log.info("Disabled StockBazaari source configuration for user #{} via Telegram", appUserId);
        telegramNotificationService.answerCallbackQuery(callbackId, "StockBazaari disabled for user #" + appUserId + ".");
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean isAuthorizedCallbackChat(Map<String, Object> callback) {
        if (authorizedChatId.isBlank()) {
            return false;
        }
        Object messageObject = callback.get("message");
        if (!(messageObject instanceof Map<?, ?> rawMessage)) {
            return false;
        }
        Object chatObject = ((Map<String, Object>) rawMessage).get("chat");
        if (!(chatObject instanceof Map<?, ?> rawChat)) {
            return false;
        }
        Object id = ((Map<String, Object>) rawChat).get("id");
        return id != null && authorizedChatId.equals(String.valueOf(id));
    }

    private Long parseUserId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMessage(Map<String, Object> update) {
        List<String> candidateKeys = List.of(
                "message",
                "edited_message",
                "channel_post",
                "edited_channel_post"
        );

        for (String key : candidateKeys) {
            Object value = update.get(key);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }

        Object callbackQuery = update.get("callback_query");
        if (callbackQuery instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean isFromBot(Map<String, Object> message) {
        Object fromObj = message.get("from");
        if (!(fromObj instanceof Map<?, ?> from)) {
            return false;
        }
        Object isBot = from.get("is_bot");
        return Boolean.TRUE.equals(isBot);
    }

    @SuppressWarnings("unchecked")
    private String extractMessageText(Map<String, Object> message) {
        Object text = message.get("text");
        if (text instanceof String str && !str.isBlank()) {
            return str.trim();
        }

        Object caption = message.get("caption");
        if (caption instanceof String str && !str.isBlank()) {
            return str.trim();
        }

        // Inline button callbacks
        Object data = Optional.ofNullable(message.get("data")).orElse(null);
        if (data instanceof String str && !str.isBlank()) {
            return str.trim();
        }

        // For callback queries, actual payload lives under "message"
        Object callbackQuery = message.get("message");
        if (callbackQuery instanceof Map<?, ?> callbackMessage) {
            return extractMessageText((Map<String, Object>) callbackMessage);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractSenderName(Map<String, Object> message) {
        try {
            Map<String, Object> chat = (Map<String, Object>) message.get("chat");
            if (chat != null) {
                Object title = chat.get("title");
                if (title instanceof String str && !str.isBlank()) {
                    return str;
                }

                Object username = chat.get("username");
                if (username instanceof String str && !str.isBlank()) {
                    return str;
                }
            }

            Map<String, Object> from = (Map<String, Object>) message.get("from");
            if (from != null) {
                Object username = from.get("username");
                if (username instanceof String str && !str.isBlank()) {
                    return str;
                }

                String firstName = stringOrNull(from.get("first_name"));
                String lastName = stringOrNull(from.get("last_name"));
                if (firstName != null || lastName != null) {
                    List<String> nameParts = new ArrayList<>();
                    if (firstName != null) {
                        nameParts.add(firstName);
                    }
                    if (lastName != null) {
                        nameParts.add(lastName);
                    }
                    if (!nameParts.isEmpty()) {
                        return String.join(" ", nameParts);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract sender name: {}", e.getMessage());
        }

        return "telegram";
    }

    @SuppressWarnings("unchecked")
    private String buildUniqueId(Map<String, Object> update, Map<String, Object> message) {
        Object chatId = null;
        Object messageId = message.get("message_id");

        try {
            Map<String, Object> chat = (Map<String, Object>) message.get("chat");
            if (chat != null) {
                chatId = chat.get("id");
            }

            if (chatId == null) {
                Object nestedMessage = message.get("message");
                if (nestedMessage instanceof Map<?, ?> nested) {
                    Map<String, Object> nestedMap = (Map<String, Object>) nested;
                    Map<String, Object> nestedChat = (Map<String, Object>) nestedMap.get("chat");
                    if (nestedChat != null) {
                        chatId = nestedChat.get("id");
                    }
                    if (messageId == null) {
                        messageId = nestedMap.get("message_id");
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (chatId != null && messageId != null) {
            return "telegram:" + chatId + ":" + messageId;
        }

        Object updateId = update.get("update_id");
        if (updateId != null) {
            return "telegram:update:" + updateId;
        }

        return null;
    }

    private String stringOrNull(Object value) {
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }
}
