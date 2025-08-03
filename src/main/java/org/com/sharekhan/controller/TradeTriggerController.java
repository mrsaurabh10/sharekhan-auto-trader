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

        // Lookup matching script in master table
        ScriptMasterEntity script = scriptMasterRepository
                .findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                        request.getInstrument(),
                        request.getStrikePrice(),
                        request.getOptionType(),
                        request.getExpiry()
                )
                .orElseThrow(() -> new RuntimeException("Script not found in master DB"));

        // Compute final quantity using lots × lotSize
        int lotSize = script.getLotSize() != null ? script.getLotSize() : 1;
        long finalQuantity = (long) request.getQuantity() * lotSize;

        TriggerTradeRequestEntity entity = TriggerTradeRequestEntity.builder()
                .symbol(request.getInstrument())
                .scripCode(script.getScripCode())
                .exchange(request.getExchange())
                .instrumentType(script.getInstrumentType())
                .strikePrice(request.getStrikePrice())
                .optionType(request.getOptionType())
                .expiry(request.getExpiry())
                .entryPrice(request.getEntryPrice())
                .stopLoss(request.getStopLoss())
                .target1(request.getTarget1())
                .target2(request.getTarget2())
                .target3(request.getTarget3())
                .trailingSl(request.getTrailingSl())
                .quantity(finalQuantity)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .createdAt(LocalDateTime.now())
                .intraday(request.getIntraday())
                .build();

        TriggerTradeRequestEntity saved = triggerTradeRequestRepository.save(entity);

        String key = request.getExchange() + entity.getScripCode(); // e.g., NC2885
        webSocketSubscriptionService.subscribeToScrip(key);

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
