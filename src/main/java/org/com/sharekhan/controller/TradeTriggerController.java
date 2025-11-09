package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeTriggerController {

    private final TradeExecutionService tradeExecutionService;

    @PostMapping("/trigger-on-price")
    public ResponseEntity<?> createTriggerTrade(@RequestBody TriggerRequest request) {
        // Delegate to service which will enforce quantity requirement when required
        TriggerTradeRequestEntity saved = tradeExecutionService.executeTrade(request, true);
        return ResponseEntity.ok(saved);
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
