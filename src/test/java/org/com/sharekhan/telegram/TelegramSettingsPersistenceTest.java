package org.com.sharekhan.telegram;

import org.com.sharekhan.service.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","app.telegram.webhook-secret=test-secret"}, showSql=false)
@Import(TelegramSettingsService.class)
class TelegramSettingsPersistenceTest {
    @Autowired TelegramSettingsService service;
    @Autowired TelegramUserSettingsRepository rows;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;
    @MockBean TelegramNotificationService telegram;
    @Test void hashedConnectionPersistsAndReplayCannotRelinkAfterReload() {
        when(telegram.botUsername()).thenReturn("test_bot"); when(telegram.isBotConfigured()).thenReturn(true);
        service.save(4L,new TelegramSettingsService.Request("vaibhavhubli"));
        String token=service.link(4L).url().split("connect_")[1];
        em.clear(); assertNotNull(rows.findById(4L).orElseThrow().getLinkHash());
        service.handleConnectionMessage(Map.of("text","/start connect_"+token,
            "chat",Map.of("type","private","id",12345),"from",Map.of("id",12345,"username","vaibhavhubli")));
        em.clear(); assertEquals("12345",service.get(4L).chatId());
        service.handleConnectionMessage(Map.of("text","/start connect_"+token,
            "chat",Map.of("type","private","id",54321),"from",Map.of("id",54321,"username","vaibhavhubli")));
        em.clear(); assertEquals("12345",service.get(4L).chatId()); assertNull(rows.findById(4L).orElseThrow().getLinkHash());
    }
}
