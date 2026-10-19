package org.com.sharekhan.controller;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.strategy.run.StrategyRunService;
import org.com.sharekhan.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/strategies") @RequiredArgsConstructor
public class StrategyRunController {
    private final StrategyRunService runs;
    private final CurrentUserService users;
    @GetMapping("/runs") public ResponseEntity<?> list(@RequestParam(required=false) Long userId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) {
        return ResponseEntity.ok(runs.list(users.scopedUserId(userId),users.isAdmin(),page,size));
    }
    @PostMapping("/runs/{id}/close") public ResponseEntity<?> close(@PathVariable String id) {
        try {return ResponseEntity.ok(runs.close(id,users.currentAppUserIdOrNull(),users.isAdmin()));}
        catch(IllegalArgumentException missing) {return ResponseEntity.status(404).body(Map.of("error",missing.getMessage()));}
    }
    @PostMapping("/subscriptions/{id}/close-and-pause") public ResponseEntity<?> closeAll(@PathVariable Long id) {
        runs.closeAllAndPause(id,users.currentAppUserIdOrNull(),users.isAdmin());
        return ResponseEntity.accepted().body(Map.of("status","CLOSING","message","Strategy paused; run exits will reconcile in background"));
    }
}
