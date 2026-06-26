package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRangeRunResponse;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReportRequest;
import org.com.sharekhan.dto.backtest.BacktestReportResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.service.BacktestDailyReplayService;
import org.com.sharekhan.service.BacktestReportService;
import org.com.sharekhan.service.BacktestReplayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestReplayService backtestReplayService;
    private final BacktestDailyReplayService backtestDailyReplayService;
    private final BacktestReportService backtestReportService;

    @Value("${app.admin.token:}")
    private String adminToken;

    @PostMapping("/trade/{tradeSetupId}/replay")
    public ResponseEntity<?> replayTrade(
            @RequestHeader(value = "X-Admin-Token", required = false) String headerToken,
            @PathVariable Long tradeSetupId,
            @RequestBody(required = false) BacktestReplayRequest request) {
        if (!authorized(headerToken)) {
            return ResponseEntity.status(403).body(error("Invalid or missing X-Admin-Token"));
        }
        try {
            return ResponseEntity.ok(backtestReplayService.replayTrade(tradeSetupId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        }
    }

    @PostMapping("/atr-signal/daily-replay")
    public ResponseEntity<?> replayAtrSignalTradesForDay(
            @RequestHeader(value = "X-Admin-Token", required = false) String headerToken,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!authorized(headerToken)) {
            return ResponseEntity.status(403).body(error("Invalid or missing X-Admin-Token"));
        }
        try {
            BacktestDailyReplayRunResponse response = backtestDailyReplayService.runForDate(date);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(ex.getMessage()));
        }
    }

    @PostMapping("/atr-signal/daily-replay/range")
    public ResponseEntity<?> replayAtrSignalTradesForRange(
            @RequestHeader(value = "X-Admin-Token", required = false) String headerToken,
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!authorized(headerToken)) {
            return ResponseEntity.status(403).body(error("Invalid or missing X-Admin-Token"));
        }
        try {
            BacktestDailyReplayRangeRunResponse response = backtestDailyReplayService.runForDateRange(from, to);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(ex.getMessage()));
        }
    }

    @PostMapping("/reports")
    public ResponseEntity<?> generateReport(
            @RequestHeader(value = "X-Admin-Token", required = false) String headerToken,
            @RequestBody(required = false) BacktestReportRequest request) {
        if (!authorized(headerToken)) {
            return ResponseEntity.status(403).body(error("Invalid or missing X-Admin-Token"));
        }
        try {
            BacktestReportResponse response = backtestReportService.generateReport(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(ex.getMessage()));
        }
    }

    @GetMapping("/reports/{reportId}/download")
    public ResponseEntity<Resource> downloadReport(
            @RequestHeader(value = "X-Admin-Token", required = false) String headerToken,
            @PathVariable String reportId) {
        if (!authorized(headerToken)) {
            return ResponseEntity.status(403).build();
        }
        Path path = backtestReportService.reportPath(reportId);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }

    private boolean authorized(String headerToken) {
        if (!StringUtils.hasText(adminToken)) {
            return true;
        }
        return adminToken.equals(headerToken);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "error");
        err.put("message", message);
        return err;
    }
}
