package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradingRequestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderViewController {

    @Autowired
    private TradingRequestService tradingRequestService;
    @Autowired private TradeExecutionService tradeExecutionService;

    @GetMapping("/requests")
    public ResponseEntity<?>  getRequestedOrders(@RequestParam(name = "userId", required = false) Long userId) {
            return ResponseEntity.ok(tradingRequestService.getRecentRequestsForUser(userId));
     }

    @GetMapping("/executed")
    public ResponseEntity<?> getExecuted(@RequestParam(name = "userId", required = false) Long userId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ResponseEntity.ok(tradeExecutionService.getRecentExecutionsForUser(userId, pageable));
    }
}