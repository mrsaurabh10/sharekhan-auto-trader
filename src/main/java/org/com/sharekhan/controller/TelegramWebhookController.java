package org.com.sharekhan.controller;

import org.com.sharekhan.service.TelegramUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramUpdateHandler telegramUpdateHandler;
    private final String secretToken;

    public TelegramWebhookController(
            TelegramUpdateHandler telegramUpdateHandler,
            @Value("${app.telegram.webhook-secret:}") String secretToken) {
        this.telegramUpdateHandler = telegramUpdateHandler;
        this.secretToken = secretToken != null ? secretToken.trim() : "";
        if (this.secretToken.isBlank()) {
            log.info("Telegram webhook secret is not configured; webhook requests will not require the secret header.");
        } else {
            log.info("Telegram webhook secret is configured ({} chars).", this.secretToken.length());
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveUpdate(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String headerSecret) {

        if (isSecretEnforced() && !secretToken.equals(headerSecret)) {
            log.warn("Rejected Telegram webhook request due to invalid or missing secret token (header length={})",
                    headerSecret != null ? headerSecret.length() : 0);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid secret token");
        }

        telegramUpdateHandler.handleUpdate(body);
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/webhook")
    public ResponseEntity<String> readinessProbe() {
        return ResponseEntity.ok("ready");
    }

    private boolean isSecretEnforced() {
        return secretToken != null && !secretToken.isBlank();
    }
}
