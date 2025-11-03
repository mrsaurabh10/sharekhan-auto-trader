package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.PriceTriggerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DebugController {

    private final PriceTriggerService priceTriggerService;

    // Simple debug endpoint to invoke monitorOpenTrades for testing
    @GetMapping("/api/debug/monitor")
    public ResponseEntity<String> monitor(@RequestParam("scrip") Integer scripCode, @RequestParam("ltp") double ltp) {
        priceTriggerService.monitorOpenTrades(scripCode, ltp);
        return ResponseEntity.ok("Invoked monitorOpenTrades for scrip=" + scripCode + ", ltp=" + ltp);
    }
}

