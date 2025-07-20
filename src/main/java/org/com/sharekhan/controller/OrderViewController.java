package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderViewController {

    //private final RequestedOrderService requestedOrderService;
    //private final TradeSetupService tradeSetupService;

//    @GetMapping("/requested")
//    public ResponseEntity<List<RequestedOrderResponse>> getRequestedOrders() {
//        return ResponseEntity.ok(requestedOrderService.getLatestRequestedOrders(10));
//    }
//
//    @GetMapping("/trade-setup")
//    public ResponseEntity<List<TradeSetupResponse>> getTradeSetups() {
//        return ResponseEntity.ok(tradeSetupService.getLatestTradeSetups(10));
//    }
}