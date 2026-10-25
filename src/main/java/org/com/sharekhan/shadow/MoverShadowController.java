package org.com.sharekhan.shadow;

import org.com.sharekhan.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/strategies/mover-shadow")
public class MoverShadowController {
    private final MoverShadowReport reports;
    private final CurrentUserService users;
    public MoverShadowController(MoverShadowReport reports,CurrentUserService users){this.reports=reports;this.users=users;}
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(required=false) Long userId) {
        Long current=users.currentAppUserIdOrNull();
        if(current==null&&!users.isAdmin())return ResponseEntity.status(401).build();
        if(userId!=null&&!Objects.equals(userId,current)&&!users.isAdmin())return ResponseEntity.status(403).build();
        Long selected=userId!=null?userId:current;
        if(selected==null||selected<=0)return ResponseEntity.badRequest().body(Map.of("message","Select an account"));
        try{return ResponseEntity.ok(reports.history(selected));}
        catch(Exception unavailable){return ResponseEntity.status(503).body(Map.of("message","Historical summaries are unavailable"));}
    }
    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam(required=false) LocalDate day,@RequestParam(required=false) Long userId) {
        Long current=users.currentAppUserIdOrNull();
        if(current==null&&!users.isAdmin())return ResponseEntity.status(401).build();
        if(userId!=null&&!Objects.equals(userId,current)&&!users.isAdmin())return ResponseEntity.status(403).build();
        Long selected=userId!=null?userId:current;
        if(selected==null||selected<=0)return ResponseEntity.badRequest().body(Map.of("message","Select an account"));
        try{return ResponseEntity.ok(reports.report(day==null?LocalDate.now(MoverShadowRecorder.ZONE):day,selected));}
        catch(MoverShadowReport.BusyException busy){return ResponseEntity.status(429).body(Map.of("message","A report is already running; retry shortly"));}
        catch(IllegalArgumentException bad){return ResponseEntity.badRequest().body(Map.of("message",bad.getMessage()));}
        catch(Exception unavailable){return ResponseEntity.status(503).body(Map.of("message","Shadow data is unavailable; trading is unaffected"));}
    }
}
