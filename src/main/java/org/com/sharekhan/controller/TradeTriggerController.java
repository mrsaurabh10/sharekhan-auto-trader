package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeTriggerController {

    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final TradeExecutionService tradeExecutionService;

    @PostMapping("/trigger-on-price")
    public ResponseEntity<?> createTriggerTrade(@RequestBody TriggerRequest request) {
        // If userId is not passed we accept it; service will fallback to default customer/app user handling.
        TriggerTradeRequestEntity saved  = tradeExecutionService.executeTrade(request);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/requests/user/{userId}")
    public ResponseEntity<?> getRequestsForUser(@PathVariable Long userId) {
        return ResponseEntity.ok(triggerTradeRequestRepository.findTop10ByAppUserIdOrderByIdDesc(userId));
    }

    @PutMapping("/force-close/{scripCode}")
    public ResponseEntity<String> forceCloseTrade(@PathVariable int scripCode) {
        boolean result = tradeExecutionService.forceCloseByScripCode(scripCode);
        if (result) {
            return ResponseEntity.ok("✅ Trade for scripCode " + scripCode + " closed forcefully.");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("⚠️ No active trade found for scripCode: " + scripCode);
        }
    }
}
