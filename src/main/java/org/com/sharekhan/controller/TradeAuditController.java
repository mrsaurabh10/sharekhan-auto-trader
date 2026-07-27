package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.repository.TradeAuditEventRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trades/audit")
@RequiredArgsConstructor
public class TradeAuditController {
    private final TradeAuditEventRepository repository;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<List<TradeAuditEventEntity>> list(@RequestParam(required = false) Long triggerRequestId,
                                                              @RequestParam(required = false) Long tradeId) {
        Long userId = currentUserService.currentAppUserIdOrNull();
        if (!currentUserService.isAdmin() && userId == null) return ResponseEntity.status(401).build();
        List<TradeAuditEventEntity> events = triggerRequestId != null
                ? repository.findByTriggerRequestIdOrderByOccurredAtAsc(triggerRequestId)
                : tradeId != null ? repository.findByTradeIdOrderByOccurredAtAsc(tradeId)
                : currentUserService.isAdmin()
                        ? repository.findTop200ByOrderByOccurredAtDesc()
                        : repository.findTop200ByAppUserIdOrderByOccurredAtDesc(userId);
        if (!currentUserService.isAdmin() && events.stream().anyMatch(event -> !userId.equals(event.getAppUserId()))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(events);
    }
}
