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
                                         @RequestParam(defaultValue = "") String q,@RequestParam(defaultValue="INTRADAY") String mode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mode.equals("SWING")?service.searchSaved(exchange,q):service.search(exchange,q));
    }
    @GetMapping("/api/stock-analysis/report")
    @ResponseBody
    public ResponseEntity<?> report(@RequestParam String exchange, @RequestParam(required=false) Integer scripCode,@RequestParam(required=false) String symbol,
                                    @RequestParam(defaultValue = "INTRADAY") String mode) {
        if(mode.equals("SWING")&&symbol!=null)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyzeSaved(exchange,symbol));
        if(scripCode==null)throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Choose a stock first.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(exchange, scripCode, mode));
    }
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<?> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason() == null ? "Unable to analyze this instrument." : ex.getReason()));
    }
}
