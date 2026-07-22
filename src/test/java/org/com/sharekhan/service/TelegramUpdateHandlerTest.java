package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TelegramUpdateHandlerTest {

    @Test
    void disablesOnlySelectedUsersStockBazaariConfigFromAuthorizedInlineButton() {
        TradingMessageService tradingMessageService = mock(TradingMessageService.class);
        UserConfigService configService = mock(UserConfigService.class);
        TelegramNotificationService notifications = mock(TelegramNotificationService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(
                tradingMessageService, configService, notifications, "-100123");

        handler.handleUpdate(callbackUpdate("disable-stockbazaari:42", "-100123"));

        verify(configService).setConfig(42L, "StockBazaari", "true", false);
        verify(notifications).answerCallbackQuery(eq("callback-1"), contains("#42"));
        verify(tradingMessageService, never()).handleRawMessage(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInlineButtonFromAnotherChat() {
        UserConfigService configService = mock(UserConfigService.class);
        TelegramNotificationService notifications = mock(TelegramNotificationService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(
                mock(TradingMessageService.class), configService, notifications, "-100123");

        handler.handleUpdate(callbackUpdate("disable-stockbazaari:42", "999"));

        verify(configService, never()).setConfig(42L, "StockBazaari", "true", false);
        verify(notifications).answerCallbackQuery(eq("callback-1"), contains("not allowed"));
    }

    private Map<String, Object> callbackUpdate(String data, String chatId) {
        return Map.of("callback_query", Map.of(
                "id", "callback-1",
                "data", data,
                "message", Map.of("chat", Map.of("id", chatId))));
    }
}
