package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.service.BrokerCredentialsService;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.util.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final BrokerCredentialsService brokerCredentialsService;
    private final AccessTokenPersistenceService accessTokenPersistenceService;
    private final CryptoService cryptoService;

    @Value("${app.admin.token:}")
    private String adminToken;

    private boolean authorized(String headerToken) {
        if (adminToken == null || adminToken.isBlank()) return true; // no admin token configured -> allow
        return adminToken.equals(headerToken);
    }

    @PostMapping("/credentials")
    public ResponseEntity<?> saveCredentials(@RequestHeader(value = "X-Admin-Token", required = false) String h,
                                             @RequestBody BrokerCredentialsEntity body) {
        if (!authorized(h)) return ResponseEntity.status(403).body("forbidden");
        if (body.getBrokerName() == null || body.getBrokerName().isBlank()) return ResponseEntity.badRequest().body("brokerName required");
        brokerCredentialsService.save(body);
        return ResponseEntity.ok().body("saved");
    }

    @PostMapping("/token")
    public ResponseEntity<?> saveToken(@RequestHeader(value = "X-Admin-Token", required = false) String h,
                                       @RequestParam String broker, @RequestParam(required = false) Long customerId,
                                       @RequestParam String token, @RequestParam long expiresInSeconds) {
        if (!authorized(h)) return ResponseEntity.status(403).body("forbidden");
        String brokerName = broker;
        Instant expiry = Instant.now().plusSeconds(expiresInSeconds - 60);
        if (customerId == null) {
            accessTokenPersistenceService.replaceToken(brokerName, token, expiry);
        } else {
            accessTokenPersistenceService.replaceTokenForCustomer(brokerName, customerId, token, expiry);
        }
        return ResponseEntity.ok().body("token saved");
    }
}
