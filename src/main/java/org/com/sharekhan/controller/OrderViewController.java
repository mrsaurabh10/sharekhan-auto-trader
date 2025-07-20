package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradingRequestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderViewController {

    @Autowired
    private TradingRequestService tradingRequestService;
    @Autowired private TradeExecutionService tradeExecutionService;

    @GetMapping("/requests")
    public ResponseEntity<?>  getRequestedOrders() {
            return ResponseEntity.ok(tradingRequestService.getRecentRequests());
     }

    @GetMapping("/executed")
    public ResponseEntity<?> getExecuted() {
        return ResponseEntity.ok(tradeExecutionService.getRecentExecutions());
    }
}