package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.CloseTradesRequest;
import org.com.sharekhan.dto.StockAtrTradeRequest;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.TradeCloseService;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradingMessageService;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.StockAtrTradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeTriggerController {

    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TradeCloseService tradeCloseService;
    private final TradingMessageService tradingMessageService;
    private final CurrentUserService currentUserService;
    private final StockAtrTradeService stockAtrTradeService;

    @Value("${app.admin.token:}")
    private String adminToken;

    private boolean authorized(String headerToken) {
        if (adminToken == null || adminToken.isBlank()) return true; // no admin token configured -> allow
        return adminToken.equals(headerToken);
    }

    @PostMapping("/trigger-all")
    public ResponseEntity<?> triggerForAllUsers(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody TriggerRequest request) {
        
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden", "message", "Invalid or missing X-Admin-Token"));
        }
        
        try {
            tradingMessageService.placeForAllSharekhanCustomers(request);
            return ResponseEntity.ok(java.util.Map.of("status", "triggered", "message", "Request submitted for all active users."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/trigger-stock-atr-all")
    public ResponseEntity<?> triggerStockAtrForAllUsers(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody StockAtrTradeRequest request) {

        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden", "message", "Invalid or missing X-Admin-Token"));
        }

        try {
            TriggerRequest triggerRequest = stockAtrTradeService.buildTriggerRequest(request);
            tradingMessageService.placeForAllSharekhanCustomers(triggerRequest);
            return ResponseEntity.ok(stockAtrTradeService.buildResponse(triggerRequest, request.getDirection()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "stock_atr_trigger_failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/close-all")
    public ResponseEntity<?> closeAllByInstrument(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody(required = false) CloseTradesRequest request) {

        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden", "message", "Invalid or missing X-Admin-Token"));
        }

        try {
            return ResponseEntity.ok(tradeCloseService.closeAllByContract(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "close_failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/close-all/{instrument}")
    public ResponseEntity<?> closeAllByInstrumentPath(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String instrument,
            @RequestParam String optionType,
            @RequestParam Double strikePrice,
            @RequestParam String expiry,
            @RequestParam(required = false) Double price,
            @RequestParam(required = false) String reason) {

        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden", "message", "Invalid or missing X-Admin-Token"));
        }

        try {
            CloseTradesRequest request = new CloseTradesRequest();
            request.setInstrument(instrument);
            request.setOptionType(optionType);
            request.setStrikePrice(strikePrice);
            request.setExpiry(expiry);
            request.setPrice(price);
            request.setReason(reason);
            return ResponseEntity.ok(tradeCloseService.closeAllByContract(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "close_failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/trigger-on-price")
    public ResponseEntity<?> createTriggerTrade(@RequestBody TriggerRequest request) {
        // If userId is not passed we accept it; service will fallback to default customer/app user handling.
        if (!currentUserService.isAdmin()) {
            request.setUserId(currentUserService.currentAppUserIdOrNull());
        }
        TriggerTradeRequestEntity saved  = tradeExecutionService.executeTrade(request);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/manual-execute")
    public ResponseEntity<?> manualExecuteTrade(@RequestBody TriggerRequest request) {
        if (!currentUserService.isAdmin()) {
            request.setUserId(currentUserService.currentAppUserIdOrNull());
        }
        TriggeredTradeSetupEntity saved = tradeExecutionService.createExecutedTrade(request);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/trigger/{id}")
    public ResponseEntity<?> triggerSavedTrade(@PathVariable Long id) {
        var opt = triggerTradeRequestRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        TriggerTradeRequestEntity request = opt.get();
        if (!currentUserService.isAdmin()) {
            Long currentUserId = currentUserService.currentAppUserIdOrNull();
            if (currentUserId == null || request.getAppUserId() == null || !currentUserId.equals(request.getAppUserId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: request does not belong to user");
            }
        }
        TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(request, true);
        if (executed != null) {
            triggerTradeRequestRepository.delete(request);
        }
        return ResponseEntity.ok(executed != null ? executed : Map.of("status", "pending", "message", "LTP unavailable; request remains pending"));
    }

    @GetMapping("/requests/user/{userId}")
    public ResponseEntity<?> getRequestsForUser(@PathVariable Long userId) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        return ResponseEntity.ok(triggerTradeRequestRepository.findTop10ByAppUserIdOrderByIdDesc(scopedUserId));
    }

    @PutMapping("/force-close/{scripCode}")
    public ResponseEntity<String> forceCloseTrade(@PathVariable int scripCode, @RequestParam(required = false) Double price) {
        boolean result = tradeExecutionService.forceCloseByScripCode(scripCode, price);
        if (result) {
            return ResponseEntity.ok("✅ Trade for scripCode " + scripCode + " closed forcefully.");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("⚠️ No active trade found for scripCode: " + scripCode);
        }
    }

    @GetMapping("/recent-executions")
    public ResponseEntity<List<TriggeredTradeSetupEntity>> recentExecutions(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "scope", defaultValue = "own") String scope) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String setupScope = normalizeSetupScopeForSession(scope);
        return ResponseEntity.ok(scopedUserId == null
                ? tradeExecutionService.getRecentExecutions()
                : tradeExecutionService.getRecentExecutionsForUser(scopedUserId, setupScope));
    }

    private String normalizeSetupScopeForSession(String scope) {
        String normalized = scope == null || scope.isBlank() ? "own" : scope.trim().toLowerCase();
        if (currentUserService.isAdmin() && "own".equals(normalized)) {
            return "user";
        }
        return normalized;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<TriggerTradeRequestEntity>> pendingRequests() {
        List<TriggerTradeRequestEntity> list = tradeExecutionService.getPendingRequests();
        if (!currentUserService.isAdmin()) {
            Long currentUserId = currentUserService.currentAppUserIdOrNull();
            list = list.stream()
                    .filter(r -> currentUserId != null && currentUserId.equals(r.getAppUserId()))
                    .toList();
        }
        return ResponseEntity.ok(list);
    }
}
