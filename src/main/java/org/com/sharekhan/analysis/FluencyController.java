package org.com.sharekhan.analysis;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.Map;
@RestController @RequestMapping("/api/stock-analysis/fluency")
public class FluencyController {
 private final FluencyService service;public FluencyController(FluencyService service){this.service=service;}
 @GetMapping public ResponseEntity<?> report(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report());}
 @PostMapping("/capture") public ResponseEntity<?> capture(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.capture());}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","Saved research model book temporarily unavailable; no study result inferred."));}
}
