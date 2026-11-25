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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class TradeAnalyticsController {
    private final TradeAnalyticsService tradeAnalyticsService;
    private final GeminiTradeInsightService geminiTradeInsightService;
    private final CurrentUserService currentUserService;
    private final org.com.sharekhan.service.TradeAnalyticsPdfService pdfService;

    @GetMapping("/trades")
    public ResponseEntity<TradeAnalyticsResponse> getTradeAnalytics(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "source", required = false) List<String> sources,
            @RequestParam(name = "brokerCredentialsId", required = false) Long brokerCredentialsId,
            @RequestParam(name = "intraday", required = false) Boolean intraday,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "ai", defaultValue = "false") boolean ai) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String analyticsScope = normalizeAnalyticsScopeForSession(scope);
        List<String> normalizedSources = normalizeSources(sources);
        TradeAnalyticsResponse response;
        if (normalizedSources.size() > 1) {
            response = tradeAnalyticsService.getTradeAnalyticsForSources(
                    scopedUserId,
                    from,
                    to,
                    symbol,
                    normalizedSources,
                    brokerCredentialsId,
                    intraday,
                    analyticsScope
            );
        } else {
            String source = normalizedSources.isEmpty() ? null : normalizedSources.get(0);
            response = analyticsScope == null
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
        }
        if (ai) {
            response = geminiTradeInsightService.addNarrative(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/trades/report.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> downloadReport(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "source", required = false) List<String> sources,
            @RequestParam(name = "brokerCredentialsId", required = false) Long brokerCredentialsId,
            @RequestParam(name = "intraday", required = false) Boolean intraday,
            @RequestParam(name = "scope", required = false) String scope) throws java.io.IOException {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String analyticsScope = normalizeAnalyticsScopeForSession(scope);
        var report = tradeAnalyticsService.getReportData(scopedUserId, from, to, symbol,
                normalizeSources(sources), brokerCredentialsId, intraday, analyticsScope);
        var filters = report.analytics().getFilters();
        String filename = "trade-analytics-" + filters.getFrom() + "-to-" + filters.getTo() + ".pdf";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdfService.generate(report));
    }

    @GetMapping("/sources")
    public ResponseEntity<List<String>> getAnalyticsSources(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "scope", required = false) String scope) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String analyticsScope = normalizeAnalyticsScopeForSession(scope);
        return ResponseEntity.ok(tradeAnalyticsService.getAvailableSources(scopedUserId, analyticsScope));
    }

    private String normalizeAnalyticsScopeForSession(String scope) {
        if (!currentUserService.isAdmin()) return "own";
        return scope == null || scope.isBlank() ? null : scope.trim().toLowerCase();
    }

    private List<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .flatMap(source -> Arrays.stream(source.split(",")))
                .map(String::trim)
                .filter(source -> !source.isBlank())
                .distinct()
                .toList();
    }
}
