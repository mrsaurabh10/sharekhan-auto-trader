package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.service.ShoonyaQuoteService;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.com.sharekhan.service.ShoonyaOAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/shoonya")
@RequiredArgsConstructor
public class ShoonyaController {
    private final ShoonyaQuoteService quoteService;
    private final ShoonyaInstrumentMasterService instrumentMasterService;
    private final ShoonyaOAuthService oauthService;
    private final TokenStoreService tokenStoreService;

    @GetMapping("/oauth/authorize-url")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> authorizationUrl() {
        try {
            return ResponseEntity.ok(Map.of("authorizationUrl", oauthService.authorizationUrl()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/oauth/exchange")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> exchangeAuthorizationCode(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        try {
            String code = body == null ? null : body.get("code");
            AuthTokenResult token = oauthService.exchangeAuthorizationCode(code);
            tokenStoreService.updateToken(Broker.SHOONYA, token.token(), token.expiresIn());
            return ResponseEntity.ok(Map.of("status", "success", "message", "Shoonya OAuth access token stored"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /** Public OAuth redirect target registered with Shoonya. The code is immediately exchanged and never rendered. */
    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(@RequestParam(required = false) String code) {
        try {
            AuthTokenResult token = oauthService.exchangeAuthorizationCode(code);
            tokenStoreService.updateToken(Broker.SHOONYA, token.token(), token.expiresIn());
            return ResponseEntity.ok("<html><body><h2>Shoonya connected</h2><p>You may close this window.</p></body></html>");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("<html><body><h2>Shoonya connection failed</h2><p>Please retry the login.</p></body></html>");
        }
    }

    @GetMapping("/quotes")
    public ResponseEntity<?> quotes(@RequestParam String exchange,
                                    @RequestParam(required = false) String token,
                                    @RequestParam(required = false) String symbol) {
        try {
            return ResponseEntity.ok(quoteService.getQuotes(exchange, token, symbol).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/instruments/refresh")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> refreshInstruments(@RequestParam(defaultValue = "NFO") String exchange) {
        try {
            int rows = instrumentMasterService.refresh(exchange);
            return ResponseEntity.ok(Map.of("status", "success", "exchange", exchange.toUpperCase(), "rows", rows));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
