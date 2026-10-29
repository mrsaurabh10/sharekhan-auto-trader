package org.com.sharekhan.analysis;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Controller
public class StockAnalysisController {
    private final StockAnalysisService service;
    public StockAnalysisController(StockAnalysisService service) { this.service = service; }

    @GetMapping("/stock-analysis")
    public String page() { return "stock-analysis"; }

    @GetMapping("/api/stock-analysis/instruments")
    @ResponseBody
    public ResponseEntity<?> instruments(@RequestParam(defaultValue = "NC") String exchange,
                                         @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(exchange, q));
    }
    @GetMapping("/api/stock-analysis/report")
    @ResponseBody
    public ResponseEntity<?> report(@RequestParam String exchange, @RequestParam int scripCode,
                                    @RequestParam(defaultValue = "INTRADAY") String mode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(exchange, scripCode, mode));
    }
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<?> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason() == null ? "Unable to analyze this instrument." : ex.getReason()));
    }
}
