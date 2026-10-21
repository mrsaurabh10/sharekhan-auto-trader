package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TelegramNotificationServiceTest {

    @Test
    void entryPromptHasThreeVersionedButtonsAndReturnsAcknowledgedMessageId() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(), eq(String.class))).thenReturn(ResponseEntity.ok(
                "{\"ok\":true,\"result\":{\"message_id\":123}}"));
        TelegramNotificationService service = new TelegramNotificationService("fake", "12345", rest);
        assertEquals(123L, service.sendEntryActionMessage(9L, "Order remains open", "88:token"));
        ArgumentCaptor<org.springframework.http.HttpEntity> payload = ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(rest).postForEntity(endsWith("/sendMessage"), payload.capture(), eq(String.class));
        org.json.JSONObject body = new org.json.JSONObject((java.util.Map<?, ?>) payload.getValue().getBody());
        org.json.JSONArray buttons = body.getJSONObject("reply_markup").getJSONArray("inline_keyboard").getJSONArray(0);
        assertEquals(3, buttons.length());
        assertEquals("entry:retry:88:token", buttons.getJSONObject(0).getString("callback_data"));
        assertEquals("entry:market:88:token", buttons.getJSONObject(1).getString("callback_data"));
        assertEquals("entry:cancel:88:token", buttons.getJSONObject(2).getString("callback_data"));
    }

    @Test
    void telegramFailureLeavesMessageUndeliveredForRecovery() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"ok\":false}"));
        assertNull(new TelegramNotificationService("fake", "12345", rest)
                .sendEntryActionMessage(9L, "Order", "88:token"));
    }

    @Test
    void privateChatAuthorizesOnlyItsOwnerAndGroupRequiresAdmin() {
        RestTemplate rest = mock(RestTemplate.class);
        TelegramNotificationService privateChat = new TelegramNotificationService("fake", "12345", rest);
        assertTrue(privateChat.isAuthorizedEntryActor("12345"));
        assertFalse(privateChat.isAuthorizedEntryActor("54321"));
        verifyNoInteractions(rest);
        TelegramNotificationService group = new TelegramNotificationService("fake", "-12345", rest);
        when(rest.postForEntity(anyString(), any(), eq(String.class))).thenReturn(
                ResponseEntity.ok("{\"ok\":true,\"result\":{\"status\":\"member\"}}"),
                ResponseEntity.ok("{\"ok\":true,\"result\":{\"status\":\"administrator\"}}"));
        assertFalse(group.isAuthorizedEntryActor("42"));
        assertTrue(group.isAuthorizedEntryActor("42"));
    }
    @Test void exitAlertRequiresAcknowledgementAndUnconfiguredDeliveryIsNotReportedAsSuccess() {
        RestTemplate rest=mock(RestTemplate.class);
        when(rest.postForEntity(anyString(),any(),eq(String.class))).thenReturn(
            ResponseEntity.ok("{\"ok\":false}"),ResponseEntity.ok("{\"ok\":true}"));
        var service=new TelegramNotificationService("fake","12345",rest);
        assertFalse(service.sendExitAttention(4L,"Verify existing order"));
        assertTrue(service.sendExitAttention(4L,"Verify existing order"));
        assertFalse(new TelegramNotificationService("","",rest).sendExitAttention(4L,"Not configured"));
        verify(rest,times(2)).postForEntity(anyString(),any(),eq(String.class));
    }

    @Test void personalRoutingIsAccountScopedAndOperatorActionsRemainShared() {
        var rest=mock(RestTemplate.class);
        when(rest.postForEntity(anyString(),any(),eq(String.class))).thenReturn(ResponseEntity.ok("{\"ok\":true}"));
        var service=new TelegramNotificationService("fake","shared",rest);
        var rows=mock(org.com.sharekhan.telegram.TelegramUserSettingsRepository.class);
        var row=new org.com.sharekhan.telegram.TelegramUserSettings();row.setAppUserId(4L);row.setChatId("12345");
        when(rows.findById(4L)).thenReturn(java.util.Optional.of(row));
        when(rows.findById(5L)).thenReturn(java.util.Optional.empty());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"personalSettings",rows);
        service.sendTradeMessageForUser(4L,"Filled","Your order");
        service.sendExitAttention(4L,"Review your exit");
        service.sendTradeMessageForUser(5L,"Filled","Other user");
        service.sendTradeMessageForUser(4L,"Source control","Action","disable-stockbazaari:4");
        var requests=ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(rest,times(4)).postForEntity(endsWith("/sendMessage"),requests.capture(),eq(String.class));
        var destinations=requests.getAllValues().stream().map(r->((java.util.Map<?,?>)r.getBody()).get("chat_id")).toList();
        assertEquals(java.util.List.of("12345","12345","shared","shared"),destinations);
    }
    @Test void personalSendFailureDoesNotLeakToSharedChat() {
        var rest=mock(RestTemplate.class);
        when(rest.postForEntity(anyString(),any(),eq(String.class))).thenThrow(new RuntimeException("offline"));
        var service=new TelegramNotificationService("fake","shared",rest);
        var rows=mock(org.com.sharekhan.telegram.TelegramUserSettingsRepository.class);
        var row=new org.com.sharekhan.telegram.TelegramUserSettings();row.setChatId("12345");
        when(rows.findById(4L)).thenReturn(java.util.Optional.of(row));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"personalSettings",rows);
        assertFalse(service.sendExitAttention(4L,"Review exit"));
        verify(rest,times(1)).postForEntity(anyString(),any(),eq(String.class));
    }
}
