package org.com.sharekhan.controller;

import org.com.sharekhan.dto.ModifyOrderRequest;
import org.com.sharekhan.dto.UpdateTargetsRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;
    private final TriggeredTradeSetupRepository triggeredTradeSetupRepository;

    public TradeExecutionController(TradeExecutionService tradeExecutionService, TriggeredTradeSetupRepository triggeredTradeSetupRepository) {
        this.tradeExecutionService = tradeExecutionService;
        this.triggeredTradeSetupRepository = triggeredTradeSetupRepository;
    }

    @PostMapping("/square-off/{id}")
    public ResponseEntity<String> squareOff(@PathVariable Long id, @RequestParam(required = false) Double price) {
        try {
            tradeExecutionService.squareOffTrade(id, price);
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

    @PostMapping("/exit-order/{id}/modify")
    public ResponseEntity<?> modifyExitOrder(@PathVariable Long id, @RequestBody ModifyOrderRequest request) {
        if (request == null || request.getPrice() == null || request.getPrice() <= 0) {
            return ResponseEntity.badRequest().body("Invalid price supplied for exit order modification.");
        }
        try {
            TradeExecutionService.ModifyExitOrderResult result =
                    tradeExecutionService.modifyExitOrderPrice(id, request.getPrice(), "MANUAL_MODIFY");
            if (result.isSuccess()) {
                return ResponseEntity.ok(result.getMessage());
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(result.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to modify exit order: " + e.getMessage());
        }
    }

    @PutMapping("/execution/{id}")
    public ResponseEntity<?> updateExecution(@PathVariable Long id, @RequestBody UpdateTargetsRequest update) {
        return triggeredTradeSetupRepository.findById(id)
                .map(trade -> {
                    boolean changed = false;
                    if (update.getEntryPrice() != null) {
                        trade.setEntryPrice(update.getEntryPrice());
                        changed = true;
                    }
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
                    if (update.getQuantity() != null) {
                        trade.setQuantity(update.getQuantity());
                        changed = true;
                    }
                    if (update.getUseSpotPrice() != null) {
                        trade.setUseSpotPrice(update.getUseSpotPrice());
                        changed = true;
                    }
                    if (update.getSpotScripCode() != null) {
                        trade.setSpotScripCode(update.getSpotScripCode());
                        changed = true;
                    }
                    // Added granular spot flags
                    if (update.getUseSpotForEntry() != null) {
                        trade.setUseSpotForEntry(update.getUseSpotForEntry());
                        changed = true;
                    }
                    if (update.getUseSpotForSl() != null) {
                        trade.setUseSpotForSl(update.getUseSpotForSl());
                        changed = true;
                    }
                    if (update.getUseSpotForTarget() != null) {
                        trade.setUseSpotForTarget(update.getUseSpotForTarget());
                        changed = true;
                    }

                    if (changed) {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                        if (!isAdmin && update.getUserId() != null && trade.getAppUserId() != null && !trade.getAppUserId().equals(update.getUserId())) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: cannot update another user's execution");
                        }
                        TriggeredTradeSetupEntity saved = triggeredTradeSetupRepository.save(trade);
                        return ResponseEntity.ok(saved);
                    }
                    return ResponseEntity.badRequest().body("No updatable fields provided");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
