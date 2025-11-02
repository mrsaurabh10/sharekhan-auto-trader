package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.UpdateTargetsRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;
    private final TriggeredTradeSetupRepository triggeredTradeSetupRepository;

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

    @PutMapping("/execution/{id}")
    public ResponseEntity<?> updateExecution(@PathVariable Long id, @RequestBody UpdateTargetsRequest update) {
        return triggeredTradeSetupRepository.findById(id)
                .map(trade -> {
                    boolean changed = false;
                    if (update.getStopLoss() != null) {
                        trade.setStopLoss(update.getStopLoss());
                        changed = true;
                    }
                    if (update.getTarget1() != null) {
                        trade.setTarget1(update.getTarget1());
                        changed = true;
                    }
                    if (update.getTarget2() != null) {
                        trade.setTarget2(update.getTarget2());
                        changed = true;
                    }
                    if (update.getTarget3() != null) {
                        trade.setTarget3(update.getTarget3());
                        changed = true;
                    }
                    if (update.getIntraday() != null) {
                        trade.setIntraday(update.getIntraday());
                        changed = true;
                    }
                    if (changed) {
                        TriggeredTradeSetupEntity saved = triggeredTradeSetupRepository.save(trade);
                        return ResponseEntity.ok(saved);
                    }
                    return ResponseEntity.badRequest().body("No updatable fields provided");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}