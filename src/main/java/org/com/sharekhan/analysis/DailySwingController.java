package org.com.sharekhan.analysis;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-analysis/swing")
public class DailySwingController {
    private final DailySwingService service;
    public DailySwingController(DailySwingService service){this.service=service;}
    @GetMapping public ResponseEntity<?> latest(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.state());}
    @GetMapping("/history") public ResponseEntity<?> history(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history());}
    @GetMapping("/reports/{day}") public ResponseEntity<?> report(@PathVariable @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate day){return service.report(day).<ResponseEntity<?>>map(r->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r)).orElseGet(()->ResponseEntity.notFound().build());}
    @PostMapping("/scan") public ResponseEntity<?> scan(){boolean accepted=service.requestScan();return ResponseEntity.status(accepted?202:200).body(Map.of("accepted",accepted,"message",accepted?"Research scan started. Results are shared with signed-in users.":"A scan is running, was recently attempted, or this session's report is already saved."));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","Saved swing research is temporarily unavailable."));}
}
