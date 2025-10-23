package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeTriggerController {

    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final ScriptMasterRepository scriptMasterRepository;
        private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final TradeExecutionService tradeExecutionService;

    @PostMapping("/trigger-on-price")
    public ResponseEntity<?> createTriggerTrade(@RequestBody TriggerRequest request) {
        TriggerTradeRequestEntity saved  = tradeExecutionService.executeTrade(request);
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
}
