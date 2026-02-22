package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.PriceTriggerService;
import org.com.sharekhan.ws.WebSocketClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DebugController {

    private final PriceTriggerService priceTriggerService;
    private final WebSocketClientService webSocketClientService;

    // Simple debug endpoint to invoke monitorOpenTrades for testing
    @GetMapping("/api/debug/monitor")
    public ResponseEntity<String> monitor(@RequestParam("scrip") Integer scripCode, @RequestParam("ltp") double ltp) {
        priceTriggerService.monitorOpenTrades(scripCode, ltp);
        return ResponseEntity.ok("Invoked monitorOpenTrades for scrip=" + scripCode + ", ltp=" + ltp);
    }

    @GetMapping("/api/debug/evaluate")
    public ResponseEntity<String> evaluate(@RequestParam("scrip") Integer scripCode, @RequestParam("ltp") double ltp) {
        priceTriggerService.evaluatePriceTrigger(scripCode, ltp);
        return ResponseEntity.ok("Invoked evaluatePriceTrigger for scrip=" + scripCode + ", ltp=" + ltp);
    }

    @GetMapping("/api/debug/simulate-ltp")
    public ResponseEntity<String> simulateLtp(@RequestParam("scrip") Integer scripCode, @RequestParam("ltp") double ltp) {
        webSocketClientService.processLtpUpdate(scripCode, ltp);
        return ResponseEntity.ok("Simulated LTP update for scrip=" + scripCode + ", ltp=" + ltp);
    }
}
