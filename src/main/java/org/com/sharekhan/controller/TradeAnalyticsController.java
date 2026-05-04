package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.service.TradeAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class TradeAnalyticsController {
    private final TradeAnalyticsService tradeAnalyticsService;

    @GetMapping("/trades")
    public ResponseEntity<TradeAnalyticsResponse> getTradeAnalytics(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "brokerCredentialsId", required = false) Long brokerCredentialsId,
            @RequestParam(name = "intraday", required = false) Boolean intraday) {
        return ResponseEntity.ok(tradeAnalyticsService.getTradeAnalytics(
                userId,
                from,
                to,
                symbol,
                source,
                brokerCredentialsId,
                intraday
        ));
    }
}
