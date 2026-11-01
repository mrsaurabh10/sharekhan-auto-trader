package org.com.sharekhan.analysis;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/stock-analysis/vcp")
public class VcpResearchController {
    private final VcpResearchService service;
    public VcpResearchController(VcpResearchService service){this.service=service;}
    @GetMapping public ResponseEntity<?> report(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report());}
}
