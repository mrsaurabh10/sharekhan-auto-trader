package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeRequestController {

    private final TriggerTradeRequestRepository tradeRequestRepository;

    @PostMapping("/cancel-request/{id}")
    public ResponseEntity<String> cancelRequest(@PathVariable Long id) {
        return tradeRequestRepository.findById(id)
                .map(request -> {
                    // Optional: Check status (e.g. only cancel if still "PENDING")
                    tradeRequestRepository.deleteById(id);
                    return ResponseEntity.ok("Request cancelled");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}