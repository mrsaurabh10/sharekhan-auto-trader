package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.GeminiTradeInsightService;
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
    private final GeminiTradeInsightService geminiTradeInsightService;
    private final CurrentUserService currentUserService;

    @GetMapping("/trades")
    public ResponseEntity<TradeAnalyticsResponse> getTradeAnalytics(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "brokerCredentialsId", required = false) Long brokerCredentialsId,
            @RequestParam(name = "intraday", required = false) Boolean intraday,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "ai", defaultValue = "false") boolean ai) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String analyticsScope = normalizeAnalyticsScopeForSession(scope);
        TradeAnalyticsResponse response = analyticsScope == null
                ? tradeAnalyticsService.getTradeAnalytics(
                        scopedUserId,
                        from,
                        to,
                        symbol,
                        source,
                        brokerCredentialsId,
                        intraday
                )
                : tradeAnalyticsService.getTradeAnalytics(
                        scopedUserId,
                        from,
                        to,
                        symbol,
                        source,
                        brokerCredentialsId,
                        intraday,
                        analyticsScope
                );
        if (ai) {
            response = geminiTradeInsightService.addNarrative(response);
        }
        return ResponseEntity.ok(response);
    }

    private String normalizeAnalyticsScopeForSession(String scope) {
        String normalized = scope == null || scope.isBlank() ? null : scope.trim().toLowerCase();
        if (currentUserService.isAdmin() && (normalized == null || "own".equals(normalized))) {
            return "all";
        }
        return normalized;
    }
}
