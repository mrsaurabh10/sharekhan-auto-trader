package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.ShoonyaQuoteService;
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

    @GetMapping("/quotes")
    public ResponseEntity<?> quotes(@RequestParam String exchange, @RequestParam String token) {
        try {
            return ResponseEntity.ok(quoteService.getQuotes(exchange, token).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
