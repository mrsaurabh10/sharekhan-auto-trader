package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;

    @PostMapping("/square-off/{id}")
    public ResponseEntity<String> squareOff(@PathVariable Long id) {
        try {
            tradeExecutionService.squareOffTrade(id);
            return ResponseEntity.ok("Trade square off initiated");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to square off: " + e.getMessage());
        }
    }

    @PostMapping("/move-sl-to-cost/{tradeId}")
    public ResponseEntity<String> moveStopLossToCost(@PathVariable Long tradeId) {
        boolean updated = tradeExecutionService.moveStopLossToCost(tradeId);
        if (updated) {
            return ResponseEntity.ok("Stop Loss moved to cost.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Failed to update stop loss.");
        }
    }
}