package org.com.sharekhan.analysis;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestController @RequestMapping("/api/stock-analysis/company")
public class CompanyAnalysisController {
 private final CompanyAnalysisService service;private final SavedDailyAnalysis daily;
 public CompanyAnalysisController(CompanyAnalysisService service,SavedDailyAnalysis daily){this.service=service;this.daily=daily;}
 @GetMapping public ResponseEntity<?> report(@RequestParam(defaultValue="RELIANCE")String symbol){return response(service.report(symbol,null));}
 @GetMapping("/symbols") public ResponseEntity<?> symbols(){return response(daily.members().stream().map(StockDailyHistoryStore.Member::symbol).toList());}
 @PostMapping public ResponseEntity<?> assess(@RequestBody CompanyAnalysisService.Financials input){return response(service.report(input.symbol(),input));}
 private ResponseEntity<?> response(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> invalid(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",e.getReason()==null?"Assessment unavailable":e.getReason()));}
}
