package org.com.sharekhan.controller;

import org.com.sharekhan.dto.ModifyOrderRequest;
import org.com.sharekhan.dto.UpdateTargetsRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.AtrPreviousDayTradeLevelService;
import org.com.sharekhan.service.PriceTriggerService;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;
    private final TriggeredTradeSetupRepository triggeredTradeSetupRepository;
    private final CurrentUserService currentUserService;
    private final PriceTriggerService priceTriggerService;
    private final AtrPreviousDayTradeLevelService atrPreviousDayTradeLevelService;

    public TradeExecutionController(TradeExecutionService tradeExecutionService,
                                    TriggeredTradeSetupRepository triggeredTradeSetupRepository,
                                    CurrentUserService currentUserService,
                                    PriceTriggerService priceTriggerService,
                                    AtrPreviousDayTradeLevelService atrPreviousDayTradeLevelService) {
        this.tradeExecutionService = tradeExecutionService;
        this.triggeredTradeSetupRepository = triggeredTradeSetupRepository;
        this.currentUserService = currentUserService;
        this.priceTriggerService = priceTriggerService;
        this.atrPreviousDayTradeLevelService = atrPreviousDayTradeLevelService;
    }

    @PostMapping("/square-off/{id}")
    public ResponseEntity<String> squareOff(@PathVariable Long id,
                                            @RequestParam(required = false) Double price,
                                            @RequestParam(required = false) TriggeredTradeStatus exitOrderStatus) {
        TriggeredTradeStatus requestedExitOrderStatus = exitOrderStatus != null
                ? exitOrderStatus
                : TriggeredTradeStatus.EXIT_ORDER_PLACED;
        if (requestedExitOrderStatus != TriggeredTradeStatus.EXIT_ORDER_PLACED
                && requestedExitOrderStatus != TriggeredTradeStatus.TARGET_ORDER_PLACED) {
            return ResponseEntity.badRequest()
                    .body("exitOrderStatus must be EXIT_ORDER_PLACED or TARGET_ORDER_PLACED");
        }
        if (!canMutateTrade(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: trade does not belong to user");
        }
        try {
            tradeExecutionService.squareOffTrade(id, price, requestedExitOrderStatus);
            return ResponseEntity.ok("Trade square off initiated");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to square off: " + e.getMessage());
        }
    }

    @PostMapping("/move-sl-to-cost/{tradeId}")
    public ResponseEntity<String> moveStopLossToCost(@PathVariable Long tradeId,
                                                      @RequestParam(required = false) String reference) {
        if (!canMutateTrade(tradeId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: trade does not belong to user");
        }
        TradeExecutionService.CostStopReference costReference = null;
        if (reference != null && !reference.isBlank()) {
            try {
                costReference = TradeExecutionService.CostStopReference.valueOf(reference.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body("reference must be SPOT or PREMIUM");
            }
        }
        return switch (tradeExecutionService.moveStopLossToCost(tradeId, costReference)) {
            case UPDATED_SPOT -> ResponseEntity.ok("Stop Loss moved to spot entry cost.");
            case UPDATED_PREMIUM_BREAK_EVEN -> ResponseEntity.ok("Stop Loss moved to option premium net break-even (charges included).");
            case REFERENCE_CHOICE_REQUIRED -> ResponseEntity.badRequest()
                    .body("Choose whether cost SL should use SPOT or PREMIUM.");
            case INVALID_COST_PRICE -> ResponseEntity.badRequest().body("A valid entry cost is unavailable for this trade.");
            case TRADE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Trade not found.");
        };
    }

    @PostMapping("/execution/{id}/reset-to-executed")
    public ResponseEntity<String> resetExitStateToExecuted(@PathVariable Long id) {
        if (!canMutateTrade(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: trade does not belong to user");
        }
        TradeExecutionService.EndOfDayExitResetResult result =
                tradeExecutionService.resetNonIntradayExitOrderIfInactive(id);
        return switch (result) {
            case RESET -> ResponseEntity.ok("Trade reset to EXECUTED; exit-order state cleared.");
            case SKIPPED_FILLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Exit order is filled; trade cannot be reset to EXECUTED.");
            case SKIPPED_BROKER_UNAVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Broker order is still active or its status cannot be verified; trade was not reset.");
            case SKIPPED_NOT_ELIGIBLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Only non-intraday trades in an exit-order state can be reset to EXECUTED.");
        };
    }

    @PostMapping("/exit-order/{id}/modify")
    public ResponseEntity<?> modifyExitOrder(@PathVariable Long id, @RequestBody ModifyOrderRequest request) {
        if (!canMutateTrade(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: trade does not belong to user");
        }
        if (request == null || request.getPrice() == null || request.getPrice() <= 0) {
            return ResponseEntity.badRequest().body("Invalid price supplied for exit order modification.");
        }
        try {
            TradeExecutionService.ModifyExitOrderResult result =
                    tradeExecutionService.modifyExitOrderPrice(id, request.getPrice(), "MANUAL_MODIFY");
            if (result.isSuccess()) {
                return ResponseEntity.ok(result.getMessage());
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(result.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to modify exit order: " + e.getMessage());
        }
    }

    @PutMapping("/execution/{id}")
    public ResponseEntity<?> updateExecution(@PathVariable Long id, @RequestBody UpdateTargetsRequest update) {
        return triggeredTradeSetupRepository.findById(id)
                .map(trade -> {
                    if (!currentUserService.isAdmin() && !ownedByCurrentUser(trade.getAppUserId())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: cannot update another user's execution");
                    }
                    boolean changed = false;
                    boolean tslChanged = false;
                    boolean recalculatedAtrLevels = false;
                    if (update.getEntryPrice() != null && atrPreviousDayTradeLevelService.appliesTo(trade)) {
                        var levels = atrPreviousDayTradeLevelService.recalculate(trade, update.getEntryPrice());
                        if (levels.isEmpty()) {
                            return ResponseEntity.badRequest()
                                    .body("Cannot recalculate ATR levels: the existing execution has no valid ATR risk distance");
                        }
                        trade.setEntryPrice(update.getEntryPrice());
                        trade.setStopLoss(levels.get().stopLoss());
                        trade.setTarget1(levels.get().target1());
                        trade.setTarget2(levels.get().target2());
                        trade.setTarget3(levels.get().target3());
                        recalculatedAtrLevels = true;
                        changed = true;
                    } else if (update.getEntryPrice() != null) {
                        trade.setEntryPrice(update.getEntryPrice());
                        changed = true;
                    }
                    // An ATR prior-day entry edit owns SL/T1/T2/T3, even if the
                    // UI also submits the values currently shown on screen.
                    if (!recalculatedAtrLevels && update.getStopLoss() != null) {
                        trade.setStopLoss(update.getStopLoss());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget1() != null) {
                        trade.setTarget1(update.getTarget1());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget2() != null) {
                        trade.setTarget2(update.getTarget2());
                        changed = true;
                    }
                    if (!recalculatedAtrLevels && update.getTarget3() != null) {
                        trade.setTarget3(update.getTarget3());
                        changed = true;
                    }
                    if (update.getIntraday() != null) {
                        trade.setIntraday(update.getIntraday());
                        changed = true;
                    }
                    if (update.getTslEnabled() != null) {
                        tslChanged = !update.getTslEnabled().equals(trade.getTslEnabled());
                        trade.setTslEnabled(update.getTslEnabled());
                        changed = true;
                    }
                    if (update.getQuantity() != null) {
                        trade.setQuantity(update.getQuantity());
                        changed = true;
                    }
                    if (update.getUseSpotPrice() != null) {
                        trade.setUseSpotPrice(update.getUseSpotPrice());
                        changed = true;
                    }
                    if (update.getSpotScripCode() != null) {
                        trade.setSpotScripCode(update.getSpotScripCode());
                        changed = true;
                    }
                    // Added granular spot flags
                    if (update.getUseSpotForEntry() != null) {
                        trade.setUseSpotForEntry(update.getUseSpotForEntry());
                        changed = true;
                    }
                    if (update.getUseSpotForSl() != null) {
                        trade.setUseSpotForSl(update.getUseSpotForSl());
                        changed = true;
                    }
                    if (update.getUseSpotForTarget() != null) {
                        trade.setUseSpotForTarget(update.getUseSpotForTarget());
                        changed = true;
                    }

                    if (changed) {
                        TriggeredTradeSetupEntity saved = triggeredTradeSetupRepository.save(trade);
                        reEvaluateOptionTradeAfterTslChange(saved, tslChanged);
                        return ResponseEntity.ok(saved);
                    }
                    return ResponseEntity.badRequest().body("No updatable fields provided");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * TSL target handling is driven by traded-option ticks. Re-run that path on a
     * user change so an already-hit option target follows the newly selected TSL
     * behavior immediately, rather than waiting for an unrelated subsequent tick.
     */
    private void reEvaluateOptionTradeAfterTslChange(TriggeredTradeSetupEntity trade, boolean tslChanged) {
        if (!tslChanged || trade == null || trade.getScripCode() == null || usesSpotForTarget(trade)) {
            return;
        }
        priceTriggerService.reEvaluateOptionTradeAfterTslChange(trade.getId());
    }

    private boolean usesSpotForTarget(TriggeredTradeSetupEntity trade) {
        return Boolean.TRUE.equals(trade.getUseSpotForTarget())
                || (trade.getUseSpotForTarget() == null && Boolean.TRUE.equals(trade.getUseSpotPrice()));
    }

    private boolean canMutateTrade(Long tradeId) {
        if (currentUserService.isAdmin()) {
            return true;
        }
        return triggeredTradeSetupRepository.findById(tradeId)
                .map(trade -> ownedByCurrentUser(trade.getAppUserId()))
                .orElse(false);
    }

    private boolean ownedByCurrentUser(Long appUserId) {
        Long currentUserId = currentUserService.currentAppUserIdOrNull();
        return currentUserId != null && appUserId != null && currentUserId.equals(appUserId);
    }
}
