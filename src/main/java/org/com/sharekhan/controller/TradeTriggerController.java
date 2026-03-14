package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradingMessageService;
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
    private final TradingMessageService tradingMessageService;

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

    @PostMapping("/trigger-on-price")
    public ResponseEntity<?> createTriggerTrade(@RequestBody TriggerRequest request) {
        // If userId is not passed we accept it; service will fallback to default customer/app user handling.
        TriggerTradeRequestEntity saved  = tradeExecutionService.executeTrade(request);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/manual-execute")
    public ResponseEntity<?> manualExecuteTrade(@RequestBody TriggerRequest request) {
        TriggeredTradeSetupEntity saved = tradeExecutionService.createExecutedTrade(request);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/requests/user/{userId}")
    public ResponseEntity<?> getRequestsForUser(@PathVariable Long userId) {
        return ResponseEntity.ok(triggerTradeRequestRepository.findTop10ByAppUserIdOrderByIdDesc(userId));
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
    public ResponseEntity<List<TriggeredTradeSetupEntity>> recentExecutions() {
        return ResponseEntity.ok(tradeExecutionService.getRecentExecutions());
    }

    @GetMapping("/pending")
    public ResponseEntity<List<TriggerTradeRequestEntity>> pendingRequests() {
        List<TriggerTradeRequestEntity> list = tradeExecutionService.getPendingRequests();
        return ResponseEntity.ok(list);
    }
}
