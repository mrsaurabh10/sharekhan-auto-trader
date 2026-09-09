package org.com.sharekhan.controller;

import org.com.sharekhan.service.TelegramUpdateHandler;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramWebhookControllerTest {
    @Test
    void entryActionsRequireConfiguredAndMatchingWebhookSecret() {
        TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
        Map<String, Object> update = Map.of("callback_query", Map.of("data", "entry:cancel:88:token"));
        assertThat(new TelegramWebhookController(handler, "").receiveUpdate(update, null).getStatusCode().value()).isEqualTo(403);
        TelegramWebhookController secured = new TelegramWebhookController(handler, "secret");
        assertThat(secured.receiveUpdate(update, "wrong").getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(handler);
        assertThat(secured.receiveUpdate(update, "secret").getStatusCode().value()).isEqualTo(200);
        verify(handler).handleUpdate(update);
    }
}
