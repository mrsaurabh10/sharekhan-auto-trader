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

    @Test
    void dispatchesEntryButtonOnlyForAuthorizedActorAndChat() {
        TelegramNotificationService notifications = mock(TelegramNotificationService.class);
        TradeExecutionService execution = mock(TradeExecutionService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(mock(TradingMessageService.class),
                mock(UserConfigService.class), notifications, "-100123");
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "tradeExecutionService", execution);
        org.mockito.Mockito.when(notifications.isAuthorizedEntryActor("42")).thenReturn(true);
        org.mockito.Mockito.when(execution.handleEntryAction(88L, "token", "market")).thenReturn("Repriced");
        handler.handleUpdate(Map.of("callback_query", Map.of("id", "cb", "data", "entry:market:88:token",
                "from", Map.of("id", 42), "message", Map.of("chat", Map.of("id", "-100123")))));
        verify(execution).handleEntryAction(88L, "token", "market");
        verify(notifications).sendTradeMessage(eq("Entry action — trade #88"), eq("Repriced"));
    }

    @Test
    void rejectsEntryActionFromUnauthorizedSenderInConfiguredChat() {
        TelegramNotificationService notifications = mock(TelegramNotificationService.class);
        TradeExecutionService execution = mock(TradeExecutionService.class);
        TradingMessageService messages = mock(TradingMessageService.class);
        TelegramUpdateHandler handler = new TelegramUpdateHandler(messages,
                mock(UserConfigService.class), notifications, "-100123");
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "tradeExecutionService", execution);
        handler.handleUpdate(callbackUpdate("entry:cancel:88:token", "-100123"));
        org.mockito.Mockito.verifyNoInteractions(execution, messages);
        verify(notifications).answerCallbackQuery(eq("callback-1"), contains("not allowed"));
    }

    private Map<String, Object> callbackUpdate(String data, String chatId) {
        return Map.of("callback_query", Map.of(
                "id", "callback-1",
                "data", data,
                "message", Map.of("chat", Map.of("id", chatId))));
    }
}
