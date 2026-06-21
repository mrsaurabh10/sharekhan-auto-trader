package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.service.BacktestDailyReplayService;
import org.com.sharekhan.service.BacktestReplayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestReplayService backtestReplayService;
    private final BacktestDailyReplayService backtestDailyReplayService;

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
