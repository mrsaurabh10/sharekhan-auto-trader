package org.com.sharekhan.analysis;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/stock-analysis/hammer")
public class HammerResearchController {
    private final HammerResearchService service;
    public HammerResearchController(HammerResearchService service){this.service=service;}
    @GetMapping public ResponseEntity<?> report(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report());}
}
