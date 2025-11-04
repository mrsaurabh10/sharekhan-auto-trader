package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.UpdateTargetsRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeRequestController {

    private final TriggerTradeRequestRepository tradeRequestRepository;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;

    @PostMapping("/cancel-request/{id}")
    public ResponseEntity<String> cancelRequest(@PathVariable Long id, @RequestParam(name = "userId", required = false) Long userId) {
        return tradeRequestRepository.findById(id)
                .map(request -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                    if (!isAdmin && userId != null && request.getCustomerId() != null && !request.getCustomerId().equals(userId)) {
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
                    if (update.getStopLoss() != null) {
                        request.setStopLoss(update.getStopLoss());
                        changed = true;
                    }
                    if (update.getTarget1() != null) {
                        request.setTarget1(update.getTarget1());
                        changed = true;
                    }
                    if (update.getTarget2() != null) {
                        request.setTarget2(update.getTarget2());
                        changed = true;
                    }
                    if (update.getTarget3() != null) {
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
                    if (changed) {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                        if (!isAdmin && update.getUserId() != null && request.getCustomerId() != null && !request.getCustomerId().equals(update.getUserId())) {
                            return ResponseEntity.status(403).body("Forbidden: cannot modify another user's request");
                        }
                        TriggerTradeRequestEntity saved = tradeRequestRepository.save(request);
                        return ResponseEntity.ok(saved);
                    }
                    return ResponseEntity.badRequest().body("No updatable fields provided");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}