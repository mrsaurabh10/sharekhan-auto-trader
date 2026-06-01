package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.logging.TradeEventLogger;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceTriggerService {

    private final TriggerTradeRequestRepository triggerRepo;
    private final TriggeredTradeSetupRepository triggeredRepo;
    private final TradeExecutionService tradeExecutionService;
    private final PlatformTransactionManager transactionManager;
    private final ScriptMasterRepository scriptMasterRepository;
    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final LtpCacheService ltpCacheService;
    private final MStockLtpService mStockLtpService;
    private final MStockInstrumentResolver instrumentResolver;
    private final SharekhanHistoricalService sharekhanHistoricalService;
    private final ScripExecutorManager scripExecutorManager;

    public void evaluatePriceTrigger(Integer scripCode, double ltp) {
        // Check if current time is after 9:20 AM IST
         LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
         if (now.isBefore(LocalTime.of(9, 20))) {
             log.debug("Skipping price trigger evaluation before 9:20 AM. Current time: {}", now);
             return;
         }

        try {
            // 1. Check triggers where scripCode is the TRADED instrument
            List<TriggerTradeRequestEntity> candidates = triggerRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
            );

            for (TriggerTradeRequestEntity trigger : candidates) {
                // Check if entry is based on spot price (using granular flag or legacy flag)
                boolean isSpotEntry = Boolean.TRUE.equals(trigger.getUseSpotForEntry()) 
                        || (trigger.getUseSpotForEntry() == null && Boolean.TRUE.equals(trigger.getUseSpotPrice()));

                // If entry is based on spot, we ignore updates on the traded instrument for entry trigger
                if (isSpotEntry) {
                    continue;
                }
                
                if (trigger.getEntryPrice() == null) continue;

                double tolerance = 1.10;

                if (rejectIfEntryPriceGuardFails(trigger, trigger.getScripCode(), ltp, "option LTP", tolerance, false)) {
                    continue;
                }

                if (ltp >= trigger.getEntryPrice()) {
                    int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.TRIGGERED.name());
                    if (claimed == 1) {
                        String conditionSummary = String.format("option LTP %.2f >= entry %.2f", ltp, trigger.getEntryPrice());
                        TradeEventLogger.logEntryTriggered(trigger, ltp, "OPTION_LTP", conditionSummary);
                        log.info("🚀 Entry condition met for {} at LTP: {}", trigger.getSymbol(), ltp);

                        // convert request -> executed entity and run execution flow
                        trigger.setStatus(TriggeredTradeStatus.TRIGGERED); // Update entity status reference
                        TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(trigger);
                        
                        if (executed != null) {
                            log.info("✅ Trigger {} converted to live trade and marked as TRIGGERED", trigger.getId());
                        } else {
                            // rollback claim
                            triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.TRIGGERED.name(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name());
                            log.warn("⚠️ Trigger {} execution skipped (likely due to missing LTP). Keeping request for retry.", trigger.getId());
                        }
                    }
                }
            }

            // 2. Check triggers where scripCode is the SPOT instrument
            List<TriggerTradeRequestEntity> spotCandidates = triggerRepo.findBySpotScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
            );

            for (TriggerTradeRequestEntity trigger : spotCandidates) {
                // Check if entry is based on spot price
                boolean isSpotEntry = Boolean.TRUE.equals(trigger.getUseSpotForEntry()) 
                        || (trigger.getUseSpotForEntry() == null && Boolean.TRUE.equals(trigger.getUseSpotPrice()));

                // Only process if entry is based on spot
                if (!isSpotEntry) {
                    continue;
                }
                
                if (trigger.getEntryPrice() == null) continue;

                double entryPrice = trigger.getEntryPrice();
                double tolerance = 1.006;
                boolean isPE = "PE".equalsIgnoreCase(trigger.getOptionType());

                Integer referenceScrip = trigger.getSpotScripCode() != null ? trigger.getSpotScripCode() : trigger.getScripCode();
                if (rejectIfEntryPriceGuardFails(trigger, referenceScrip, ltp, "spot LTP", tolerance, isPE)) {
                    continue;
                }

                boolean conditionMet;
                if (isPE) {
                    // For PE, trigger if spot price goes BELOW entry price
                    conditionMet = ltp <= entryPrice;
                } else {
                    // For CE (or others), trigger if spot price goes ABOVE entry price
                    conditionMet = ltp >= entryPrice;
                }

                if (conditionMet) {
                    int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.TRIGGERED.name());
                    if (claimed == 1) {
                        String conditionSummary = isPE
                                ? String.format("spot LTP %.2f <= entry %.2f", ltp, entryPrice)
                                : String.format("spot LTP %.2f >= entry %.2f", ltp, entryPrice);
                        TradeEventLogger.logEntryTriggered(trigger, ltp, "SPOT_LTP", conditionSummary);
                        log.info("🚀 Spot Entry condition met for {} ({}) at SpotLTP: {}", trigger.getSymbol(), trigger.getOptionType(), ltp);

                        // convert request -> executed entity and run execution flow
                        trigger.setStatus(TriggeredTradeStatus.TRIGGERED); // Update entity status reference
                        TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(trigger);
                        
                        if (executed != null) {
                            log.info("✅ Trigger {} converted to live trade and marked as TRIGGERED", trigger.getId());
                        } else {
                            triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.TRIGGERED.name(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name());
                            log.warn("⚠️ Trigger {} execution skipped (likely due to missing LTP). Keeping request for retry.", trigger.getId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error evaluating price trigger for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    private boolean rejectIfEntryPriceGuardFails(TriggerTradeRequestEntity trigger,
                                                 Integer referenceScrip,
                                                 double currentPrice,
                                                 String currentPriceLabel,
                                                 double toleranceMultiplier,
                                                 boolean downsideEntry) {
        Double entryPrice = trigger.getEntryPrice();
        if (entryPrice == null || entryPrice <= 0d) {
            return false;
        }

        Optional<ReferencePrice> openPrice = getTodayOpenReferencePrice(referenceScrip);
        if (openPrice.isPresent()) {
            ReferencePrice open = openPrice.get();
            if (isComparableToEntryPrice(open.price(), entryPrice)) {
                if (rejectIfReferencePriceInvalid(
                        trigger,
                        open.label(),
                        open.price(),
                        entryPrice,
                        toleranceMultiplier,
                        downsideEntry)) {
                    return true;
                }
            } else {
                log.warn("Skipping entry-gap validation against {}={} for trigger {} because it is not comparable with entryPrice={}",
                        open.label(), open.price(), trigger.getId(), entryPrice);
            }
        }

        return rejectIfReferencePriceInvalid(
                trigger,
                currentPriceLabel,
                currentPrice,
                entryPrice,
                toleranceMultiplier,
                downsideEntry);
    }

    private Optional<ReferencePrice> getTodayOpenReferencePrice(Integer referenceScrip) {
        if (referenceScrip == null) {
            return Optional.empty();
        }

        Double openingPrice = ltpCacheService.getTodayOpeningPrice(referenceScrip);
        if (openingPrice != null) {
            return Optional.of(new ReferencePrice("captured open", openingPrice));
        }

        OptionalDouble openPriceOpt = sharekhanHistoricalService.getTodayOpenPrice(referenceScrip);
        return openPriceOpt.isPresent()
                ? Optional.of(new ReferencePrice("historical open", openPriceOpt.getAsDouble()))
                : Optional.empty();
    }

    private boolean rejectIfReferencePriceInvalid(TriggerTradeRequestEntity trigger,
                                                  String priceLabel,
                                                  double referencePrice,
                                                  double entryPrice,
                                                  double toleranceMultiplier,
                                                  boolean downsideEntry) {
        if (!Double.isFinite(referencePrice) || referencePrice <= 0d) {
            return false;
        }

        double tolerancePercent = Math.abs(toleranceMultiplier - 1) * 100;
        boolean outsideTolerance = downsideEntry
                ? referencePrice < entryPrice * (2 - toleranceMultiplier)
                : referencePrice > entryPrice * toleranceMultiplier;

        if (outsideTolerance) {
            int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
            if (claimed == 1) {
                TradeEventLogger.logGapRejection(
                        trigger,
                        priceLabel,
                        referencePrice,
                        entryPrice,
                        tolerancePercent
                );
                String direction = downsideEntry ? "below" : "above";
                log.warn("⚠️ {} {} is more than {}% {} entry price {} for trigger {}. Marking as REJECTED.",
                        priceLabel, referencePrice, tolerancePercent, direction, entryPrice, trigger.getId());
            }
            return true;
        }

        Double target1 = trigger.getTarget1();
        if (target1 != null && target1 > 0d) {
            boolean targetAlreadyReached = downsideEntry
                    ? referencePrice <= target1
                    : referencePrice >= target1;
            if (targetAlreadyReached) {
                int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.REJECTED.name());
                if (claimed == 1) {
                    log.warn("⚠️ {} {} has already reached/breached target1 {} for trigger {}. Marking as REJECTED.",
                            priceLabel, referencePrice, target1, trigger.getId());
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Open/reference price and entry should be in a similar scale for option contracts.
     * Guard against mismatched feeds (e.g. spot/index value used for option trigger).
     */
    private boolean isComparableToEntryPrice(double referencePrice, double entryPrice) {
        if (!Double.isFinite(referencePrice) || !Double.isFinite(entryPrice) || referencePrice <= 0d || entryPrice <= 0d) {
            return false;
        }
        double ratio = referencePrice / entryPrice;
        return ratio >= 0.1d && ratio <= 10d;
    }

    private record ReferencePrice(String label, double price) {
    }

    public void monitorOpenTrades(Integer scripCode, double ltp) {
        try {
            log.debug("Invoked monitorOpenTrades for scripCode={} with ltp={}", scripCode, ltp);
            
            // 1. Find trades where this scripCode is the TRADED instrument
            List<TriggeredTradeSetupEntity> trades = triggeredRepo.findByScripCodeAndStatusIn(
                    scripCode,
                    java.util.List.of(TriggeredTradeStatus.EXECUTED, TriggeredTradeStatus.TARGET_ORDER_PLACED)
            );

            for (TriggeredTradeSetupEntity trade : trades) {
                try {
                    // If any spot flag is true, we might need spot price.
                    // If spotScripCode is present, fetch spot LTP.
                    Double spotLtp = null;
                    if (trade.getSpotScripCode() != null) {
                        spotLtp = ltpCacheService.getLtp(trade.getSpotScripCode());
                    }

                    if (requiresSpotReference(trade) && spotLtp == null) {
                        spotLtp = fetchLtpFromMStock(trade.getSpotScripCode(), trade.getId(), "spot");
                        if (spotLtp == null) {
                            log.debug("Skipping trade {} evaluation on traded tick because spot LTP for scrip {} is not available yet",
                                    trade.getId(), trade.getSpotScripCode());
                            continue;
                        }
                    }

                    // If spotLtp is missing but needed, we might skip or fallback.
                    // For now, pass both tradedLtp (ltp) and spotLtp to handleTradeWithLock
                    handleTradeWithLock(trade.getId(), ltp, spotLtp);
                    
                } catch (Exception e) {
                    log.error("❌ Error handling trade {} in monitor: {}", trade.getId(), e.getMessage(), e);
                }
            }
            
            // 2. Find trades where this scripCode is the SPOT instrument (if any)
            List<TriggeredTradeSetupEntity> spotTrades = triggeredRepo.findBySpotScripCodeAndStatusIn(
                    scripCode,
                    java.util.List.of(TriggeredTradeStatus.EXECUTED, TriggeredTradeStatus.TARGET_ORDER_PLACED)
            );
             for (TriggeredTradeSetupEntity trade : spotTrades) {
                try {
                     // We have the spot LTP (ltp argument). We need the traded instrument LTP for execution.
                     Double tradedLtp = ltpCacheService.getLtp(trade.getScripCode());
                     
                     if (tradedLtp == null) {
                         tradedLtp = fetchLtpFromMStock(trade.getScripCode(), trade.getId(), "traded");
                     }

                     if (tradedLtp != null) {
                         handleTradeWithLock(trade.getId(), tradedLtp, ltp);
                     } else {
                         log.debug("Traded instrument LTP not available for trade {}, skipping evaluation.", trade.getId());
                     }
                } catch (Exception e) {
                    log.error("❌ Error handling spot-based trade {} in monitor: {}", trade.getId(), e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("❌ Error monitoring open trades for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    protected void handleTradeWithLock(Long tradeId, double tradedLtp, Double spotLtp) {
        try {
            final TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            // Execute quick transaction: read the current trade and attempt atomic claim if condition met
            Integer claimed = txTemplate.execute(status -> {
                var opt = triggeredRepo.findById(tradeId);
                if (opt.isEmpty()) return 0;
                TriggeredTradeSetupEntity persisted = opt.get();

                // Only act if still in EXECUTED or TARGET_ORDER_PLACED
                TriggeredTradeStatus currentStatus = persisted.getStatus();
                if (currentStatus != TriggeredTradeStatus.EXECUTED && currentStatus != TriggeredTradeStatus.TARGET_ORDER_PLACED) {
                    return 0;
                }

                // Determine effective prices for SL and Target
                boolean usesSpotSl = usesSpotForSl(persisted);
                boolean usesSpotTarget = usesSpotForTarget(persisted);

                Double slRefPrice = usesSpotSl ? spotLtp : tradedLtp;
                Double targetRefPrice = usesSpotTarget ? spotLtp : tradedLtp;

                if (usesSpotSl && slRefPrice == null) {
                    log.debug("Spot SL requested for trade {} but spot LTP unavailable; skipping SL evaluation", tradeId);
                }
                if (usesSpotTarget && targetRefPrice == null) {
                    log.debug("Spot target requested for trade {} but spot LTP unavailable; skipping target evaluation", tradeId);
                }

                Double slVal = persisted.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);

                // Check SL against effective reference price
                boolean slHit = false;
                if (slRefPrice != null && hasValidSl) {
                    boolean isSpotSl = usesSpotSl;
                    boolean isPE = "PE".equalsIgnoreCase(persisted.getOptionType());

                    if (isSpotSl && isPE) {
                        // For PE with Spot SL: Hit if Spot Price goes ABOVE SL
                        slHit = slRefPrice >= slVal;
                    } else {
                        // For CE (or non-spot SL): Hit if Price goes BELOW SL
                        slHit = slRefPrice <= slVal;
                    }
                }

                if (slHit) {
                    if (!tradeExecutionService.hasUsableTradedExitPrice(persisted, tradedLtp)) {
                        log.warn("SL hit for trade {} on reference price {}, but traded LTP {} looks invalid for the option. Waiting for correct option LTP.",
                                tradeId, slRefPrice, tradedLtp);
                        return 0;
                    }
                    int updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "STOP_LOSS_HIT");
                    if (updated == 0) {
                        updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.TARGET_ORDER_PLACED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "STOP_LOSS_HIT");
                    }
                    return updated;
                }

                // Check if any target hit and if we need to book lots
                if (targetRefPrice != null) {
                    // Only perform partial booking logic if TSL is enabled
                    if (Boolean.TRUE.equals(persisted.getTslEnabled())) {
                        int lotsToBook = calculateLotsToBook(persisted, targetRefPrice);
                        if (lotsToBook > 0) {
                            if (!tradeExecutionService.hasUsableTradedExitPrice(persisted, tradedLtp)) {
                                log.warn("Target hit for trade {} on reference price {}, but traded LTP {} looks invalid for the option. Waiting for correct option LTP.",
                                        tradeId, targetRefPrice, tradedLtp);
                                return 0;
                            }
                            int updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            if (updated == 0) {
                                updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.TARGET_ORDER_PLACED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            }
                            return updated;
                        }
                    } else {
                        // Standard target hit logic (any target hit -> exit all)
                        boolean isSpotTarget = usesSpotTarget;
                        boolean isPE = "PE".equalsIgnoreCase(persisted.getOptionType());

                        boolean targetHit;
                        if (isSpotTarget && isPE) {
                            // For PE with Spot Target: Hit if Spot Price goes BELOW Target
                            targetHit = (persisted.getTarget1() != null && persisted.getTarget1() > 0d && targetRefPrice <= persisted.getTarget1()) ||
                                        (persisted.getTarget2() != null && persisted.getTarget2() > 0d && targetRefPrice <= persisted.getTarget2()) ||
                                        (persisted.getTarget3() != null && persisted.getTarget3() > 0d && targetRefPrice <= persisted.getTarget3());
                        } else {
                            // For CE (or non-spot Target): Hit if Price goes ABOVE Target
                            targetHit = (persisted.getTarget1() != null && persisted.getTarget1() > 0d && targetRefPrice >= persisted.getTarget1()) ||
                                        (persisted.getTarget2() != null && persisted.getTarget2() > 0d && targetRefPrice >= persisted.getTarget2()) ||
                                        (persisted.getTarget3() != null && persisted.getTarget3() > 0d && targetRefPrice >= persisted.getTarget3());
                        }
                        
                        if (targetHit) {
                            if (!tradeExecutionService.hasUsableTradedExitPrice(persisted, tradedLtp)) {
                                log.warn("Target hit for trade {} on reference price {}, but traded LTP {} looks invalid for the option. Waiting for correct option LTP.",
                                        tradeId, targetRefPrice, tradedLtp);
                                return 0;
                            }
                            int updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            if (updated == 0) {
                                updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.TARGET_ORDER_PLACED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            }
                            return updated;
                        }
                    }
                }

                return 0;
            });

            if (claimed != null && claimed == 1) {
                // Claim succeeded — now re-load the entity (outside the short transaction) and proceed to squareOff
                TriggeredTradeSetupEntity reloaded = triggeredRepo.findById(tradeId).orElseThrow(() -> new RuntimeException("Trade not found after claim: " + tradeId));
                
                // Re-determine effective prices for logging/logic
                Double slRefPrice = usesSpotForSl(reloaded) ? spotLtp : tradedLtp;
                Double targetRefPrice = usesSpotForTarget(reloaded) ? spotLtp : tradedLtp;

                String exitReason = reloaded.getExitReason();
                boolean exitOrderAlreadyPresent = reloaded.getExitOrderId() != null && !reloaded.getExitOrderId().isBlank();
                if ("STOP_LOSS_HIT".equals(exitReason)) {
                    Double stopPriceOption = reloaded.getStopLoss();
                    boolean usesSpotSl = usesSpotForSl(reloaded);

                    boolean modified = false;
                    if (exitOrderAlreadyPresent) {
                        // Prefer the latest traded LTP so the broker order executes immediately; fall back to a configured SL.
                        Double modifyPrice = null;
                        if (Double.isFinite(tradedLtp) && tradedLtp > 0d) {
                            modifyPrice = tradedLtp;
                        } else if (stopPriceOption != null && stopPriceOption > 0d) {
                            modifyPrice = stopPriceOption;
                        }
                        if (modifyPrice != null) {
                            modified = tradeExecutionService.modifyExitOrderForStop(reloaded, modifyPrice);
                        }
                    }

                    Double triggerPriceForLog = stopPriceOption != null ? stopPriceOption : slRefPrice;
                    TradeEventLogger.logStopLossTriggered(reloaded, triggerPriceForLog, tradedLtp, spotLtp);

                    if (modified) {
                        log.warn("📉 SL hit for trade {} - modified existing exit order {} to price {}", tradeId, reloaded.getExitOrderId(), stopPriceOption);
                    } else {
                        log.warn("📉 SL hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff", tradeId, slRefPrice, tradedLtp);
                        tradeExecutionService.squareOff(reloaded, tradedLtp, "STOP_LOSS_HIT");
                    }
                } else {
                    // TARGET_HIT
                    if (exitOrderAlreadyPresent) {
                        log.info("🎯 Target hit for trade {} - existing exit order {} already placed. Modifying toward traded LTP {}.",
                                tradeId, reloaded.getExitOrderId(), tradedLtp);
                        boolean modified = tradeExecutionService.modifyExitOrderForTarget(reloaded, tradedLtp);
                        if (!modified) {
                            try {
                                reloaded.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
                                triggeredRepo.save(reloaded);
                            } catch (Exception e) {
                                log.debug("Failed to persist TARGET_ORDER_PLACED status for trade {}: {}", tradeId, e.getMessage());
                            }
                        }
                    } else if (Boolean.TRUE.equals(reloaded.getTslEnabled())) {
                        int lots = resolveCurrentLots(reloaded);
                        // If lot count cannot be derived, fall back to single-lot target exit behavior.
                        if (lots <= 1) {
                            log.info("🎯 Target hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff (Single/Unknown Lot)", tradeId, targetRefPrice, tradedLtp);
                            tradeExecutionService.squareOff(reloaded, tradedLtp, "TARGET_HIT");
                        } else {
                            handlePartialBooking(reloaded, targetRefPrice, tradedLtp, lots);
                        }
                    } else {
                        log.info("🎯 Target hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff (TSL Disabled)", tradeId, targetRefPrice, tradedLtp);
                        tradeExecutionService.squareOff(reloaded, tradedLtp, "TARGET_HIT");
                    }
                }

                // ensure pnl persisted if needed
                persistPnlIfMissing(reloaded, tradedLtp);
            } else {
                log.debug("No claim performed for trade {} (claimed={})", tradeId, claimed);
            }
        } catch (Exception e) {
            log.error("❌ Error in handleTradeWithLock for trade {}: {}", tradeId, e.getMessage(), e);
        }
    }

    private int calculateLotsToBook(TriggeredTradeSetupEntity trade, double ltp) {
        int currentLots = resolveCurrentLots(trade);
        int totalLots = resolveTotalLots(trade, currentLots);
        BookingStep step = resolveNextBookingStep(totalLots, currentLots);

        if (step == null || !isTargetHit(trade, ltp, step.targetNumber())) {
            return 0;
        }

        return Math.min(step.lotsToBook(), currentLots);
    }

    private void handlePartialBooking(TriggeredTradeSetupEntity trade, double referenceLtp, double tradedLtp, int currentLots) {
        if (currentLots <= 0) {
            currentLots = resolveCurrentLots(trade);
        }

        int totalLots = resolveTotalLots(trade, currentLots);
        if (trade.getOriginalLots() == null) {
            trade.setOriginalLots(totalLots);
            triggeredRepo.save(trade);
        }

        log.info("🎯 Target hit for trade {} with {} current lots (original: {}). Calculating partial booking.", trade.getId(), currentLots, totalLots);

        BookingStep step = resolveNextBookingStep(totalLots, currentLots);
        if (step == null || !isTargetHit(trade, referenceLtp, step.targetNumber())) {
            log.warn("Ignoring partial booking for trade {} because next target stage is not hit. currentLots={}, totalLots={}, referenceLtp={}",
                    trade.getId(), currentLots, totalLots, referenceLtp);
            return;
        }

        int lotsToBook = Math.min(step.lotsToBook(), currentLots);
        Double newStopLoss = trade.getStopLoss();
        boolean newStopLossUsesTradedInstrument = false;
        boolean target1Hit = isTargetHit(trade, referenceLtp, 1);
        Double t1OptionStopLoss = resolveT1OptionStopLoss(trade, target1Hit, tradedLtp);

        if (step.targetNumber() == 1) {
            Double optionCost = resolveOptionCost(trade);
            if (optionCost != null) {
                newStopLoss = optionCost;
                newStopLossUsesTradedInstrument = true;
            }
        } else if (step.targetNumber() == 2 && t1OptionStopLoss != null) {
            newStopLoss = t1OptionStopLoss;
            newStopLossUsesTradedInstrument = true;
        }

        if (lotsToBook <= 0) {
            log.warn("Ignoring partial booking for trade {} because calculated lotsToBook={} currentLots={}",
                    trade.getId(), lotsToBook, currentLots);
            return;
        }

        if (lotsToBook >= currentLots) {
            // Full exit
            tradeExecutionService.squareOff(trade, tradedLtp, "TARGET_HIT_FULL");
        } else {
            // Partial exit
            long originalQty = trade.getQuantity();
            ScriptMasterEntity script = scriptMasterRepository.findByScripCode(trade.getScripCode());
            int lotSize = script != null && script.getLotSize() != null ? script.getLotSize() : 1;

            long qtyToBook = (long) lotsToBook * lotSize;
            long remainingQty = originalQty - qtyToBook;
            int remainingLots = currentLots - lotsToBook;

            log.info("Partial Booking: Booking {} lots ({} qty), Remaining {} lots ({} qty)", lotsToBook, qtyToBook, remainingLots, remainingQty);

            // Create a new entity for the remaining portion
            TriggeredTradeSetupEntity remainingTrade = new TriggeredTradeSetupEntity();
            remainingTrade.setSymbol(trade.getSymbol());
            remainingTrade.setScripCode(trade.getScripCode());
            remainingTrade.setBrokerCredentialsId(trade.getBrokerCredentialsId());
            remainingTrade.setAppUserId(trade.getAppUserId());
            remainingTrade.setExchange(trade.getExchange());
            remainingTrade.setInstrumentType(trade.getInstrumentType());
            remainingTrade.setStrikePrice(trade.getStrikePrice());
            remainingTrade.setOptionType(trade.getOptionType());
            remainingTrade.setExpiry(trade.getExpiry());
            remainingTrade.setEntryPrice(trade.getEntryPrice());
            remainingTrade.setActualEntryPrice(trade.getActualEntryPrice());
            remainingTrade.setStopLoss(newStopLoss); // Updated SL
            remainingTrade.setTarget1(trade.getTarget1());
            remainingTrade.setTarget2(trade.getTarget2());
            remainingTrade.setTarget3(trade.getTarget3());
            remainingTrade.setTrailingSl(resolveTrailingT1OptionPrice(trade, target1Hit, tradedLtp));
            remainingTrade.setQuantity(remainingQty);
            remainingTrade.setLots(remainingLots);
            remainingTrade.setOriginalLots(totalLots);
            remainingTrade.setTslEnabled(trade.getTslEnabled()); // Preserve TSL flag
            remainingTrade.setIntraday(trade.getIntraday());
            remainingTrade.setStatus(TriggeredTradeStatus.EXECUTED);
            remainingTrade.setTriggeredAt(trade.getTriggeredAt());
            remainingTrade.setEntryAt(trade.getEntryAt());
            remainingTrade.setUseSpotForEntry(trade.getUseSpotForEntry());
            remainingTrade.setUseSpotForSl(newStopLossUsesTradedInstrument ? Boolean.FALSE : trade.getUseSpotForSl());
            remainingTrade.setUseSpotForTarget(trade.getUseSpotForTarget());
            remainingTrade.setSpotScripCode(trade.getSpotScripCode());
            remainingTrade.setSource(trade.getSource());
            
            // Append suffix to orderId to avoid unique constraint violation
            if (trade.getOrderId() != null) {
                remainingTrade.setOrderId(trade.getOrderId() + "-REM-" + System.currentTimeMillis());
            }

            triggeredRepo.save(remainingTrade);

            // Update current trade to be the exited portion and SAVE it so squareOff sees the new quantity
            trade.setQuantity(qtyToBook);
            trade.setLots(lotsToBook);
            triggeredRepo.save(trade);

            // IMPORTANT: Subscribe to the new remaining trade's scrip code to ensure monitoring continues
            // We do this BEFORE squareOff because squareOff might result in an immediate unsubscribe (if fully executed),
            // which would drop the refCount to 0 if we haven't incremented it for the remaining portion yet.
            String key = remainingTrade.getExchange() + remainingTrade.getScripCode();
            webSocketSubscriptionService.subscribeToScrip(key);
            
            // Always subscribe to spot scrip if it exists for the remaining trade
            if (remainingTrade.getSpotScripCode() != null) {
                ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(remainingTrade.getSpotScripCode());
                if (spotScript != null) {
                    String spotKey = spotScript.getExchange() + spotScript.getScripCode();
                    if (isSharekhanIndexSpot(spotScript)) {
                        webSocketSubscriptionService.subscribeToScripLtp(spotKey);
                    } else {
                        webSocketSubscriptionService.subscribeToScrip(spotKey);
                    }
                }
            }

            // Proceed to square off this portion
            tradeExecutionService.squareOff(trade, tradedLtp, "TARGET_HIT_PARTIAL");
        }
    }

    private BookingStep resolveNextBookingStep(int totalLots, int currentLots) {
        if (currentLots <= 0) {
            return null;
        }

        if (totalLots <= 1) {
            return new BookingStep(1, currentLots);
        }

        if (totalLots == 2) {
            return currentLots >= 2
                    ? new BookingStep(1, currentLots - 1)
                    : new BookingStep(2, currentLots);
        }

        if (totalLots == 3) {
            if (currentLots >= 3) {
                return new BookingStep(1, currentLots - 2);
            }
            return currentLots == 2
                    ? new BookingStep(2, 1)
                    : new BookingStep(3, currentLots);
        }

        int exitAtT1 = Math.max(1, (int) Math.round(totalLots * 0.4));
        int exitAtT3 = Math.max(1, totalLots - exitAtT1 - exitAtT1);
        int exitAtT2 = Math.max(1, totalLots - exitAtT1 - exitAtT3);

        int bookedLots = Math.max(0, totalLots - currentLots);
        int afterT1Booked = exitAtT1;
        int afterT2Booked = exitAtT1 + exitAtT2;

        if (bookedLots < afterT1Booked) {
            return new BookingStep(1, afterT1Booked - bookedLots);
        }
        if (bookedLots < afterT2Booked) {
            return new BookingStep(2, afterT2Booked - bookedLots);
        }
        return new BookingStep(3, currentLots);
    }

    private boolean isTargetHit(TriggeredTradeSetupEntity trade, double ltp, int targetNumber) {
        Double target = getTargetForStage(trade, targetNumber);
        if (target == null || target <= 0d) {
            return false;
        }

        boolean isSpotTarget = usesSpotForTarget(trade);
        boolean isPE = "PE".equalsIgnoreCase(trade.getOptionType());
        if (isSpotTarget && isPE) {
            return ltp <= target;
        }
        return ltp >= target;
    }

    private Double getTargetForStage(TriggeredTradeSetupEntity trade, int targetNumber) {
        if (trade == null) {
            return null;
        }
        if (targetNumber == 1) {
            return trade.getTarget1();
        }
        if (targetNumber == 2) {
            return trade.getTarget2();
        }
        if (targetNumber == 3) {
            return trade.getTarget3();
        }
        return null;
    }

    private boolean isSharekhanIndexSpot(ScriptMasterEntity spotScript) {
        Integer scripCode = spotScript.getScripCode();
        return "NC".equalsIgnoreCase(spotScript.getExchange())
                && (Integer.valueOf(20000).equals(scripCode) || Integer.valueOf(26009).equals(scripCode));
    }

    private int resolveCurrentLots(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return 0;
        }

        Integer derivedLots = deriveLotsFromQuantity(trade);
        if (derivedLots != null && derivedLots > 0) {
            if (trade.getLots() != null && trade.getLots() > 0 && !trade.getLots().equals(derivedLots)) {
                log.warn("Trade {} lots={} differs from quantity-derived lots={}; using quantity-derived lots for TSL booking.",
                        trade.getId(), trade.getLots(), derivedLots);
            }
            return derivedLots;
        }

        return trade.getLots() != null && trade.getLots() > 0 ? trade.getLots() : 1;
    }

    private Integer deriveLotsFromQuantity(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getQuantity() == null || trade.getQuantity() <= 0L || trade.getScripCode() == null) {
            return null;
        }

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(trade.getScripCode());
        if (script == null || script.getLotSize() == null || script.getLotSize() <= 0) {
            return null;
        }

        return (int) Math.ceil((double) trade.getQuantity() / script.getLotSize());
    }

    private int resolveTotalLots(TriggeredTradeSetupEntity trade, int currentLots) {
        if (trade != null && trade.getOriginalLots() != null && trade.getOriginalLots() > 0) {
            return Math.max(trade.getOriginalLots(), currentLots);
        }
        return Math.max(currentLots, 1);
    }

    private Double resolveOptionCost(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getActualEntryPrice() != null && trade.getActualEntryPrice() > 0d) {
            return trade.getActualEntryPrice();
        }
        if (trade.getEntryPrice() != null && trade.getEntryPrice() > 0d) {
            return trade.getEntryPrice();
        }
        return null;
    }

    private Double resolveT1OptionStopLoss(TriggeredTradeSetupEntity trade, boolean target1Hit, double tradedLtp) {
        if (trade == null) {
            return null;
        }
        if (trade.getTrailingSl() != null && trade.getTrailingSl() > 0d) {
            return trade.getTrailingSl();
        }
        if (!usesSpotForTarget(trade) && trade.getTarget1() != null && trade.getTarget1() > 0d) {
            return trade.getTarget1();
        }
        if (target1Hit && Double.isFinite(tradedLtp) && tradedLtp > 0d) {
            return tradedLtp;
        }
        return null;
    }

    private Double resolveTrailingT1OptionPrice(TriggeredTradeSetupEntity trade, boolean target1Hit, double tradedLtp) {
        if (trade != null && trade.getTrailingSl() != null && trade.getTrailingSl() > 0d) {
            return trade.getTrailingSl();
        }
        if (target1Hit && Double.isFinite(tradedLtp) && tradedLtp > 0d) {
            return tradedLtp;
        }
        return trade != null ? trade.getTrailingSl() : null;
    }

    private record BookingStep(int targetNumber, int lotsToBook) {
    }

    private void persistPnlIfMissing(TriggeredTradeSetupEntity originalTrade, double ltp) {
        try {
            if (originalTrade == null || originalTrade.getId() == null) return;

            var opt = triggeredRepo.findById(originalTrade.getId());
            if (opt.isEmpty()) return;

            TriggeredTradeSetupEntity saved = opt.get();

            if (saved.getExitOrderId() != null && !saved.getExitOrderId().isBlank()) {
                log.debug("Skipping persistPnlIfMissing for trade {} because exitOrderId={} is present; order polling will update status.", saved.getId(), saved.getExitOrderId());
                return;
            }

            if (saved.getPnl() != null) return;

            Double entryPriceForPnl = resolveEntryPriceForPnl(saved);
            Long quantity = saved.getQuantity();

            if (entryPriceForPnl == null || quantity == null || quantity <= 0) {
                log.debug("Cannot compute PnL for trade {} - missing entryPrice/quantity", saved.getId());
                return;
            }

            Double exitPrice = saved.getExitPrice();
            if (exitPrice == null) {
                if (!canUseLtpAsSyntheticExit(saved)) {
                    log.debug("Skipping synthetic PnL persistence for trade {} because no executed exit price is available yet (orderId={}, source={})",
                            saved.getId(), saved.getOrderId(), saved.getSource());
                    return;
                }
                if (!Double.isFinite(ltp) || ltp <= 0d) {
                    log.debug("Skipping synthetic PnL persistence for trade {} because fallback LTP is invalid: {}", saved.getId(), ltp);
                    return;
                }
                if (isImplausibleOptionPrice(saved, ltp)) {
                    log.warn("Skipping synthetic PnL persistence for trade {} because fallback LTP {} looks implausible for option trade",
                            saved.getId(), ltp);
                    return;
                }
                exitPrice = ltp;
            }

            try {
                java.math.BigDecimal exitBd = java.math.BigDecimal.valueOf(exitPrice);
                java.math.BigDecimal entryBd = java.math.BigDecimal.valueOf(entryPriceForPnl);
                java.math.BigDecimal qtyBd = java.math.BigDecimal.valueOf(quantity);
                java.math.BigDecimal rawPnlBd = exitBd.subtract(entryBd).multiply(qtyBd).setScale(2, java.math.RoundingMode.HALF_UP);
                saved.setPnl(rawPnlBd.doubleValue());
            } catch (Exception e) {
                log.warn("Failed computing PnL in persistPnlIfMissing for trade {}: {}", saved.getId(), e.getMessage());
                return;
            }

            if (saved.getExitPrice() == null) saved.setExitPrice(exitPrice);
            saved.setExitedAt(LocalDateTime.now());
            saved.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);

            triggeredRepo.save(saved);
            log.info("💾 Persisted PnL {} and marked EXITED_SUCCESS for trade {}", saved.getPnl(), saved.getId());
        } catch (Exception e) {
            log.error("❌ Error saving PnL for trade {}: {}", originalTrade == null ? "null" : originalTrade.getId(), e.getMessage(), e);
        }
    }

    private Double resolveEntryPriceForPnl(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getActualEntryPrice() != null) {
            return trade.getActualEntryPrice();
        }
        if (usesSpotReference(trade)) {
            log.warn("Cannot compute PnL for spot-referenced trade {} because actualEntryPrice is missing. entryPrice={} is a reference price, not the traded instrument fill.",
                    trade.getId(), trade.getEntryPrice());
            return null;
        }
        return trade.getEntryPrice();
    }

    private boolean canUseLtpAsSyntheticExit(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        String orderId = trade.getOrderId();
        if (orderId != null && orderId.startsWith("SIM-")) {
            return true;
        }
        String source = trade.getSource();
        return source != null && "simulator".equalsIgnoreCase(source.trim());
    }

    private boolean isImplausibleOptionPrice(TriggeredTradeSetupEntity trade, double candidatePrice) {
        if (trade == null || candidatePrice <= 0d) {
            return false;
        }
        String optionType = trade.getOptionType();
        if (optionType == null || optionType.trim().isEmpty()) {
            return false;
        }
        if (candidatePrice > 10000d) {
            return true;
        }
        Double entryReference = trade.getActualEntryPrice() != null ? trade.getActualEntryPrice() : trade.getEntryPrice();
        if (entryReference == null || entryReference <= 0d) {
            return false;
        }
        double ratio = candidatePrice / entryReference;
        return ratio > 20d || ratio < 0.02d;
    }

    private boolean usesSpotForTarget(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForTarget())) {
            return true;
        }
        return trade.getUseSpotForTarget() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private boolean usesSpotForSl(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForSl())) {
            return true;
        }
        return trade.getUseSpotForSl() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private boolean requiresSpotReference(TriggeredTradeSetupEntity trade) {
        return trade != null && trade.getSpotScripCode() != null
                && (usesSpotForSl(trade) || usesSpotForTarget(trade));
    }

    private boolean usesSpotReference(TriggeredTradeSetupEntity trade) {
        return trade != null && (Boolean.TRUE.equals(trade.getUseSpotForEntry())
                || Boolean.TRUE.equals(trade.getUseSpotForSl())
                || Boolean.TRUE.equals(trade.getUseSpotForTarget())
                || Boolean.TRUE.equals(trade.getUseSpotPrice()));
    }

    private Double fetchLtpFromMStock(Integer scripCode, Long tradeId, String priceRole) {
        if (scripCode == null) {
            return null;
        }
        try {
            ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
            if (script == null) {
                log.debug("Unable to find script master for {} scrip {} while fetching fallback LTP", priceRole, scripCode);
                return null;
            }

            Optional<String> mstockKeyOpt = instrumentResolver.resolveInstrumentKey(script);
            if (mstockKeyOpt.isEmpty()) {
                log.debug("Unable to resolve MStock instrument for {} scrip {} while fetching fallback LTP", priceRole, scripCode);
                return null;
            }

            String mstockKey = mstockKeyOpt.get();
            Map<String, Object> mstockData = mStockLtpService.fetchLtpForInstrument(mstockKey);
            if (mstockData == null) {
                return null;
            }

            Object priceObj = mstockData.get("last_price");
            if (priceObj instanceof Number) {
                double fallbackLtp = ((Number) priceObj).doubleValue();
                log.info("Fetched missing {} LTP from MStock for trade {} scrip {} instrument {}: {}",
                        priceRole, tradeId, scripCode, mstockKey, fallbackLtp);
                return fallbackLtp;
            }

            log.debug("MStock fallback LTP for {} scrip {} returned non-numeric last_price={}", priceRole, scripCode, priceObj);
            return null;
        } catch (Exception ex) {
            log.warn("Failed to fetch {} fallback LTP from MStock for trade {} scrip {}: {}",
                    priceRole, tradeId, scripCode, ex.getMessage());
            return null;
        }
    }
}
