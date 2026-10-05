package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.CsvBacktestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.*;
import java.util.Map;

@RestController @RequestMapping("/api/backtests/csv") @RequiredArgsConstructor
public class CsvBacktestController {
    private final CsvBacktestService service;
    @Value("${app.admin.token:}") private String adminToken;
    @PostMapping("/datasets")
    public ResponseEntity<?> upload(@RequestHeader(value="X-Admin-Token",required=false) String token,
                                   @RequestParam MultipartFile file,@RequestParam String symbol,@RequestParam(defaultValue="5") int inputMinutes) throws IOException {
        if(!authorized(token)) return ResponseEntity.status(403).build();
        try {return ResponseEntity.ok(service.importCsv(file.getInputStream(),file.getOriginalFilename(),symbol,inputMinutes));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
    @GetMapping("/datasets") public ResponseEntity<?> datasets(@RequestHeader(value="X-Admin-Token",required=false) String token) {
        return authorized(token)?ResponseEntity.ok(service.list()):ResponseEntity.status(403).build();
    }
    public record RunRequest(LocalDate from,LocalDate to,Double targetR,Double slippagePoints,Double costPoints,LocalTime squareOff) { }
    @PostMapping("/datasets/{id}/run")
    public ResponseEntity<?> run(@RequestHeader(value="X-Admin-Token",required=false) String token,@PathVariable String id,@RequestBody RunRequest request) {
        if(!authorized(token)) return ResponseEntity.status(403).build();
        try {return ResponseEntity.ok(service.run(id,request.from(),request.to(),request.targetR()==null?2:request.targetR(),
                request.slippagePoints()==null?0:request.slippagePoints(),request.costPoints()==null?0:request.costPoints(),request.squareOff()==null?LocalTime.of(15,20):request.squareOff()));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
    private boolean authorized(String token) {return adminToken!=null&&!adminToken.isBlank()&&adminToken.equals(token);}
}
