package org.com.sharekhan.analysis;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/stock-analysis/momentum")
public class MomentumResearchController {
    private final MomentumResearchService service;
    public MomentumResearchController(MomentumResearchService service){this.service=service;}
    @GetMapping public ResponseEntity<?> report(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report());}
}
