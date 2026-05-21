package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.StrategySubscriptionService;
import org.com.sharekhan.service.StrategyTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyTemplateService strategyTemplateService;
    private final StrategySubscriptionService strategySubscriptionService;
    private final CurrentUserService currentUserService;

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        return ResponseEntity.ok(strategyTemplateService.listTemplates());
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody StrategyApplyRequest request) {
        try {
            if (!currentUserService.isAdmin()) {
                request.setUserId(currentUserService.currentAppUserIdOrNull());
            }
            return ResponseEntity.ok(strategyTemplateService.apply(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StrategyApplyRequest request) {
        try {
            if (!currentUserService.isAdmin()) {
                request.setUserId(currentUserService.currentAppUserIdOrNull());
            }
            return ResponseEntity.ok(strategySubscriptionService.start(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<?> subscriptions(@RequestParam(name = "userId", required = false) Long userId) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        return ResponseEntity.ok(strategySubscriptionService.list(scopedUserId, currentUserService.isAdmin()));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(strategySubscriptionService.cancel(
                    id,
                    currentUserService.currentAppUserIdOrNull(),
                    currentUserService.isAdmin()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
