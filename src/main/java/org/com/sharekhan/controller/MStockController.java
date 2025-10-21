package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.service.MStockLtpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/mstock")
@RequiredArgsConstructor
public class MStockController {

    private final MStockLtpService mStockLtpService;
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;

    @GetMapping("/ltp")
    public ResponseEntity<Map<String, Object>> getLtp(@RequestParam(name = "i") List<String> instruments) {
        if (instruments == null || instruments.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "At least one 'i' query parameter is required, e.g. ?i=NSE:ACC");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Map<String, Map<String, Object>> data = mStockLtpService.fetchLtp(instruments);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to fetch MStock LTP", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> setToken(@RequestBody Map<String, Object> body) {
        // Accept JSON: { "accessToken": "...", "expiresIn": 28800 }
        String accessToken = (body.containsKey("accessToken") && body.get("accessToken") != null)
                ? body.get("accessToken").toString() : null;
        Number expiresInNum = body.containsKey("expiresIn") && body.get("expiresIn") instanceof Number
                ? (Number) body.get("expiresIn") : null;

        if (accessToken == null || accessToken.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "accessToken is required in request body");
            return ResponseEntity.badRequest().body(err);
        }

        long expiresIn = (expiresInNum != null) ? expiresInNum.longValue() : 8L * 60 * 60; // default 8h

        try {
            tokenStoreService.updateToken(Broker.MSTOCK, accessToken, expiresIn);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "token stored");
            resp.put("expiresIn", expiresIn);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to store MStock token", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyAndStore() {
        BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
        if (provider == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "No MStock auth provider registered");
            return ResponseEntity.status(404).body(err);
        }

        try {
            AuthTokenResult result = provider.loginAndFetchToken();
            if (result == null || result.token() == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("status", "error");
                err.put("message", "Provider returned no token");
                return ResponseEntity.status(500).body(err);
            }

            // persist token in DB and cache
            tokenStoreService.updateToken(Broker.MSTOCK, result.token(), result.expiresIn());

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("maskedToken", mask(result.token()));
            resp.put("expiresIn", result.expiresIn());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("MStock verify failed", e);
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    private String mask(String token) {
        if (token == null) return null;
        int len = token.length();
        if (len <= 8) return "****";
        return token.substring(0, 4) + "..." + token.substring(len - 4);
    }
}
