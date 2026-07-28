package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.ShoonyaQuoteService;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.springframework.http.ResponseEntity;
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
