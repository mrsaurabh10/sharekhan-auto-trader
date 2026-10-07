package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.strategy.MarketHistoryArchiveService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/backtests/history")
@RequiredArgsConstructor
public class MarketHistoryController {
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final MarketHistoryArchiveService archive;
    @Value("${app.admin.token:}") private String adminToken;

    /** Export archived spot candles in the existing CSV backtest input format. */
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @RequestParam String symbol,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(403).build();
        }
        try {
            var candles = archive.read(symbol, from, to);
            if (candles.isEmpty()) return ResponseEntity.notFound().build();
            StringBuilder csv = new StringBuilder("date,open,high,low,close,volume\n");
            for (var candle : candles) {
                csv.append(CSV_TIME.format(candle.getCandleTime())).append(',')
                        .append(candle.getOpenPrice()).append(',').append(candle.getHighPrice()).append(',')
                        .append(candle.getLowPrice()).append(',').append(candle.getClosePrice()).append(',')
                        .append(candle.getVolume() == null ? 0 : candle.getVolume()).append('\n');
            }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + symbol + "_5minute_" + from + "_" + to + ".csv\"")
                    .body(csv.toString());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
