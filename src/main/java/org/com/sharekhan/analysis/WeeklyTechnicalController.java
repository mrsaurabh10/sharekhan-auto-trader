package org.com.sharekhan.analysis;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/stock-analysis/weekly")
public class WeeklyTechnicalController {
 private final WeeklyTechnicalService service;private final SavedDailyAnalysis daily;
 public WeeklyTechnicalController(WeeklyTechnicalService service,SavedDailyAnalysis daily){this.service=service;this.daily=daily;}
 @GetMapping public ResponseEntity<?> report(@RequestParam(defaultValue="RELIANCE")String symbol){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(symbol));}
 @GetMapping("/symbols") public ResponseEntity<?> symbols(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(daily.members().stream().map(StockDailyHistoryStore.Member::symbol).toList());}
}
