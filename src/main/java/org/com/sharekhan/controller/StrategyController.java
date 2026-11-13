package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.StrategySubscriptionService;
import org.com.sharekhan.service.StrategyTemplateService;
import org.com.sharekhan.strategy.Fno0925MoverAtrBreakoutStrategy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Map;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyTemplateService strategyTemplateService;
    private final StrategySubscriptionService strategySubscriptionService;
    private final CurrentUserService currentUserService;
    private final org.com.sharekhan.onboarding.UserTradingAccess tradingAccess;
    private final org.com.sharekhan.service.StrategySubscriptionLifecycleService lifecycle;

    private final org.com.sharekhan.repository.StrategySubscriptionRepository subscriptions;
    private final org.com.sharekhan.strategy.batman.BatmanBooks batmanBooks;

    @GetMapping("/batman-books")
    public ResponseEntity<?> batmanBooks(){
        if(!currentUserService.isAdmin())throw new org.springframework.security.access.AccessDeniedException("Administrator required");
        return ResponseEntity.ok(batmanBooks.diagnostics());
    }

    /** Explicit one-call Simulator catch-up. Does not alter the recurring 09:20 schedule. */
    @PostMapping("/subscriptions/{id}/batman-entry-today")
    public ResponseEntity<?> enterBatmanToday(@PathVariable Long id,@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate tradingDay,
            @RequestParam(required=false) String retryOfRunId,@RequestParam(defaultValue="false") boolean marketEntry) {
        if(!currentUserService.isAdmin())throw new org.springframework.security.access.AccessDeniedException("Administrator required");
        var now=java.time.LocalDate.now(org.com.sharekhan.strategy.batman.BatmanRules.IST);
        if(!now.equals(tradingDay))return ResponseEntity.badRequest().body(Map.of("error","Explicit current trading day required"));
        var sub=subscriptions.findById(id).orElseThrow(()->new IllegalArgumentException("Subscription not found"));
        if(!"BATMAN_MONTHLY".equals(sub.getTemplateId())||!"ACTIVE".equals(sub.getStatus()))
            return ResponseEntity.badRequest().body(Map.of("error","Active Batman subscription required"));
        var request=new StrategyApplyRequest();request.setSubscriptionId(sub.getId());request.setTemplateId(sub.getTemplateId());
        request.setUserId(sub.getAppUserId());request.setBrokerCredentialsId(sub.getBrokerCredentialsId());
        request.setSymbol(sub.getSymbol());request.setLots(sub.getLots());request.setIntraday(false);
        request.setBatmanManualEntryDay(tradingDay);
        if(marketEntry && retryOfRunId==null)return ResponseEntity.badRequest().body(Map.of("error","Market-style Simulator entry requires the reviewed aborted run"));
        request.setBatmanRetryOfRunId(retryOfRunId);request.setBatmanMarketEntry(marketEntry);
        var response=strategyTemplateService.apply(request);
        sub.setLastEvaluatedAt(java.time.LocalDateTime.now(org.com.sharekhan.strategy.batman.BatmanRules.IST));
        sub.setLastEvaluationStatus(response.getStatus());sub.setLastMessage(response.getMessage());
        if(response.getTradeRequest()!=null)sub.setGeneratedTradeRequestId(response.getTradeRequest().getId());
        subscriptions.recordEvaluationIfActive(sub);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/subscriptions/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycle.pause(id, currentUserService.currentAppUserIdOrNull(), currentUserService.isAdmin()));
    }

    @PostMapping("/subscriptions/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycle.resume(id, currentUserService.currentAppUserIdOrNull(), currentUserService.isAdmin()));
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        return ResponseEntity.ok(strategyTemplateService.listTemplates());
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody StrategyApplyRequest request) {
        tradingAccess.scope(request);
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

    /**
     * Protected convenience endpoint for manually running the F&O 09:25 mover strategy.
     * The normal subscription is still the preferred path for the scheduled daily run.
     */
    @PostMapping("/fno-0925-mover/run")
    public ResponseEntity<?> runFno0925Mover(@RequestBody(required = false) StrategyApplyRequest request) {
        if (!currentUserService.isAdmin()) tradingAccess.scope(request != null ? request : new StrategyApplyRequest());
        try {
            StrategyApplyRequest effective = request != null ? request : new StrategyApplyRequest();
            effective.setTemplateId(Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID);
            if (!org.springframework.util.StringUtils.hasText(effective.getSymbol())) {
                effective.setSymbol("FNO_UNIVERSE");
            }
            if (!currentUserService.isAdmin()) {
                effective.setUserId(currentUserService.currentAppUserIdOrNull());
            }
            return ResponseEntity.ok(strategyTemplateService.apply(effective));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StrategyApplyRequest request) {
        tradingAccess.scope(request);
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
    public ResponseEntity<?> subscriptions(@RequestParam(name = "userId", required = false) Long userId,
                                           @RequestParam(name = "status", defaultValue = "ACTIVE") String status) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        try {return ResponseEntity.ok(strategySubscriptionService.list(scopedUserId, currentUserService.isAdmin(),status));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
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

    @PutMapping("/subscriptions/{id}/symbols")
    public ResponseEntity<?> updateSymbols(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String symbols = body != null ? body.get("symbols") : null;
            return ResponseEntity.ok(strategySubscriptionService.updateSymbols(
                    id,
                    symbols,
                    currentUserService.currentAppUserIdOrNull(),
                    currentUserService.isAdmin()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
