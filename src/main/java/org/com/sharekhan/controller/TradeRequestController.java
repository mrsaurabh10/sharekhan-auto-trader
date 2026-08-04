package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.UpdateTargetsRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.AtrPreviousDayTradeLevelService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeRequestController {

    private final TriggerTradeRequestRepository tradeRequestRepository;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final CurrentUserService currentUserService;
    private final AtrPreviousDayTradeLevelService atrPreviousDayTradeLevelService;

    @PostMapping("/cancel-request/{id}")
    public ResponseEntity<String> cancelRequest(@PathVariable Long id, @RequestParam(name = "userId", required = false) Long userId) {
        return tradeRequestRepository.findById(id)
                .map(request -> {
                    if (!currentUserService.isAdmin() && !ownedByCurrentUser(request.getAppUserId())) {
                        return ResponseEntity.status(403).body("Forbidden: request does not belong to user");
                    }
                    tradeRequestRepository.deleteById(id);
                    webSocketSubscriptionHelper.unsubscribeFromScrip(request.getExchange() + request.getScripCode());
                    return ResponseEntity.ok("Request cancelled");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/request/{id}")
    public ResponseEntity<?> updateRequest(@PathVariable Long id, @RequestBody UpdateTargetsRequest update) {
        return tradeRequestRepository.findById(id)
                .map(request -> {
                    boolean changed = false;
                    boolean recalculatedAtrLevels = false;
                    if (update.getEntryPrice() != null && atrPreviousDayTradeLevelService.appliesTo(request)) {
                        var levels = atrPreviousDayTradeLevelService.recalculate(request, update.getEntryPrice());
                        if (levels.isEmpty()) {
                            return ResponseEntity.badRequest().body("Cannot recalculate ATR levels: the existing request has no valid ATR risk distance");
                        }
                        request.setEntryPrice(update.getEntryPrice());
                        request.setStopLoss(levels.get().stopLoss());
                        request.setTarget1(levels.get().target1());
                        request.setTarget2(levels.get().target2());
                        request.setTarget3(levels.get().target3());
                        recalculatedAtrLevels = true;
                        changed = true;
                    } else if (update.getEntryPrice() != null) {
                        request.setEntryPrice(update.getEntryPrice());
                        changed = true;
                    }
                    // For an ATR prior-day request, an entry edit always owns SL/T1/T2/T3.
                    // This is intentional even when the UI posts its existing target values.
                    if (!recalculatedAtrLevels && update.getStopLoss() != null) {
                        request.setStopLoss(update.getStopLoss());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget1() != null) {
                        request.setTarget1(update.getTarget1());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget2() != null) {
                        request.setTarget2(update.getTarget2());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget3() != null) {
                        request.setTarget3(update.getTarget3());
                        changed = true;
                    }
                    if (update.getQuantity() != null) {
                        request.setQuantity(update.getQuantity());
                        changed = true;
                    }
                    if (update.getIntraday() != null) {
                        request.setIntraday(update.getIntraday());
                        changed = true;
                    }
                    if (update.getTslEnabled() != null) {
                        request.setTslEnabled(update.getTslEnabled());
                        changed = true;
                    }
                    if (update.getUseSpotPrice() != null) {
                        request.setUseSpotPrice(update.getUseSpotPrice());
                        changed = true;
                    }
                    if (update.getSpotScripCode() != null) {
                        request.setSpotScripCode(update.getSpotScripCode());
                        changed = true;
                    }
                    // Added granular spot flags
                    if (update.getUseSpotForEntry() != null) {
                        request.setUseSpotForEntry(update.getUseSpotForEntry());
                        changed = true;
                    }
                    if (update.getUseSpotForSl() != null) {
                        request.setUseSpotForSl(update.getUseSpotForSl());
                        changed = true;
                    }
                    if (update.getUseSpotForTarget() != null) {
                        request.setUseSpotForTarget(update.getUseSpotForTarget());
                        changed = true;
                    }

                    if (changed) {
                        if (!currentUserService.isAdmin() && !ownedByCurrentUser(request.getAppUserId())) {
                            return ResponseEntity.status(403).body("Forbidden: cannot modify another user's request");
                        }
                        TriggerTradeRequestEntity saved = tradeRequestRepository.save(request);
                        return ResponseEntity.ok(saved);
                    }
                    return ResponseEntity.badRequest().body("No updatable fields provided");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private boolean ownedByCurrentUser(Long appUserId) {
        Long currentUserId = currentUserService.currentAppUserIdOrNull();
        return currentUserId != null && appUserId != null && currentUserId.equals(appUserId);
    }
}
