package org.com.sharekhan.telegram;

import org.com.sharekhan.service.TelegramNotificationService;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramSettingsTest {
    TelegramUserSettingsRepository repository;
    TelegramNotificationService telegram;
    TelegramSettingsService service;
    TelegramUserSettings row;
    @BeforeEach void setup() {
        repository = mock(TelegramUserSettingsRepository.class);
        telegram = mock(TelegramNotificationService.class);
        when(telegram.isBotConfigured()).thenReturn(true);
        when(telegram.botUsername()).thenReturn("test_trader_bot");
        service = new TelegramSettingsService(repository, telegram, "secret");
        row = new TelegramUserSettings(); row.setAppUserId(4L); row.setUsername("vaibhavhubli");
        when(repository.lockByUserId(4L)).thenReturn(Optional.of(row));
        when(repository.findById(4L)).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }
    String link() {
        var link = service.link(4L);
        assertTrue(link.expiresAt().isAfter(Instant.now().plusSeconds(590)));
        String token = link.url().split("connect_")[1];
        assertEquals(32, token.length()); assertNotEquals(token, row.getLinkHash());
        assertEquals(TelegramSettingsService.hash(token), row.getLinkHash());
        when(repository.lockByLinkHash(row.getLinkHash())).thenReturn(Optional.of(row));
        return token;
    }
    Map<String,Object> message(String token, String username, long chatId, long senderId, String type) {
        return Map.of("text", "/start connect_"+token, "chat", Map.of("type",type,"id",chatId),
            "from",Map.of("id",senderId,"username",username,"is_bot",false));
    }
    @Test void validPrivateStartBindsExactUsernameAndConsumesSingleUseToken() {
        String token = link();
        assertTrue(service.handleConnectionMessage(message(token,"VaibhavHubli",12345,12345,"private")));
        assertEquals("12345",row.getChatId()); assertNull(row.getLinkHash()); assertNull(row.getLinkExpiresAt());
        assertTrue(service.get(4L).connected());
        // The database lookup no longer finds a consumed hash.
        when(repository.lockByLinkHash(TelegramSettingsService.hash(token))).thenReturn(Optional.empty());
        service.handleConnectionMessage(message(token,"vaibhavhubli",54321,54321,"private"));
        assertEquals("12345",row.getChatId());
        verify(telegram,never()).sendTradeMessage(any(),any());
    }
    @Test void mismatchedUsernameGroupAndSpoofedSenderNeverBind() {
        String token = link();
        service.handleConnectionMessage(message(token,"someone_else",12345,12345,"private"));
        service.handleConnectionMessage(message(token,"vaibhavhubli",12345,12345,"group"));
        service.handleConnectionMessage(message(token,"vaibhavhubli",12345,99999,"private"));
        service.handleConnectionMessage(Map.of("text","/start connect_"+token,"chat",Map.of("id",12345,"type","private"),
            "from",Map.of("id",12345,"username","vaibhavhubli","is_bot",true)));
        assertNull(row.getChatId()); assertNotNull(row.getLinkHash());
    }
    @Test void expiredMalformedAndUnknownTokensDoNotBindOrEnterTradingParser() {
        String token = link(); row.setLinkExpiresAt(Instant.now().minusSeconds(1));
        assertTrue(service.handleConnectionMessage(message(token,"vaibhavhubli",12345,12345,"private")));
        assertTrue(service.handleConnectionMessage(message("bad","vaibhavhubli",12345,12345,"private")));
        assertTrue(service.handleConnectionMessage(message("a".repeat(32),"vaibhavhubli",12345,12345,"private")));
        assertNull(row.getChatId());
        assertFalse(service.handleConnectionMessage(Map.of("text","BUY NIFTY 250")));
    }
    @Test void usernameChangeDisconnectsAndSameUsernamePreservesConnection() {
        row.setChatId("12345"); link();
        assertTrue(service.save(4L,new TelegramSettingsService.Request("@VaibhavHubli")).connected());
        assertFalse(service.save(4L,new TelegramSettingsService.Request("another_user")).connected());
        assertNull(row.getLinkHash()); assertNull(row.getLinkExpiresAt());
    }
    @Test void usernameOnlyIsPendingAndInvalidInputNeverWrites() {
        assertFalse(service.save(4L,new TelegramSettingsService.Request("@vaibhavhubli")).connected());
        assertEquals("vaibhavhubli",row.getUsername());
        clearInvocations(repository);
        for (String invalid : List.of("123456","x","<script>","name with space","@"))
            assertThrows(ResponseStatusException.class,()->service.save(4L,new TelegramSettingsService.Request(invalid)));
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void missingSecretFailsClosedAndNewLinksInvalidateOldLinks() {
        var insecure = new TelegramSettingsService(repository,telegram,"");
        assertThrows(ResponseStatusException.class,()->insecure.link(4L));
        assertTrue(insecure.handleConnectionMessage(message("a".repeat(32),"vaibhavhubli",12345,12345,"private")));
        verify(repository,never()).lockByLinkHash(any());
        String first = link(); String second = link(); assertNotEquals(first,second);
        assertEquals(TelegramSettingsService.hash(second), row.getLinkHash());
    }
    @Test void disconnectRemovesOnlyCurrentAccount() {
        service.remove(4L); verify(repository).lockByUserId(4L); verify(repository).delete(row);
    }
}
