package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TelegramNotificationServiceTest {

//    @Test
//    public void testSendTradeMessage_skippedWhenNotConfigured() {
//        RestTemplate rest = mock(RestTemplate.class);
//        TelegramNotificationServiceTest svc = new TelegramNotificationService("", "", rest);
//
//        svc.sendTradeMessage("Title", "Body");
//
//        // RestTemplate should not be invoked
//        verify(rest, never()).getForEntity(anyString(), eq(String.class));
//    }
//
//    @Test
//    public void testSendTradeMessage_sendsWhenConfigured_success() {
//        RestTemplate rest = mock(RestTemplate.class);
//        ResponseEntity<String> okResp = new ResponseEntity<>("{\"ok\":true}", HttpStatus.OK);
//        when(rest.getForEntity(anyString(), eq(String.class))).thenReturn(okResp);
//
//        TelegramNotificationService svc = new TelegramNotificationService("FAKE_TOKEN", "12345", rest);
//        svc.sendTradeMessage("T", "M");
//
//        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
//        verify(rest, times(1)).getForEntity(cap.capture(), eq(String.class));
//        String uri = cap.getValue();
//        assertTrue(uri.contains("sendMessage"));
//        assertTrue(uri.contains("chat_id=12345"));
//        assertTrue(uri.contains("T%0AM")); // encoded newline between title and message
//    }
//
//    @Test
//    public void testSendTradeMessage_handlesNon2xx() {
//        RestTemplate rest = mock(RestTemplate.class);
//        ResponseEntity<String> errResp = new ResponseEntity<>("{\"ok\":false}", HttpStatus.BAD_REQUEST);
//        when(rest.getForEntity(anyString(), eq(String.class))).thenReturn(errResp);
//
//        TelegramNotificationService svc = new TelegramNotificationService("FAKE_TOKEN", "12345", rest);
//        svc.sendTradeMessage("T", "M");
//
//        verify(rest, times(1)).getForEntity(anyString(), eq(String.class));
//    }
}
