package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
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
                
                // Check if LTP is more than tolerance % of the entry price
                if (ltp > trigger.getEntryPrice() * tolerance) {
                    log.warn("⚠️ LTP {} is more than {}% above entry price {} for trigger {}. Marking as REJECTED.", ltp, (tolerance - 1) * 100, trigger.getEntryPrice(), trigger.getId());
                    trigger.setStatus(TriggeredTradeStatus.REJECTED);
                    triggerRepo.save(trigger);
                    continue;
                }

                if (ltp >= trigger.getEntryPrice()) {
                    log.info("🚀 Entry condition met for {} at LTP: {}", trigger.getSymbol(), ltp);

                    // convert request -> executed entity and run execution flow
                    TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(trigger);
                    
                    if (executed != null) {
                        trigger.setStatus(TriggeredTradeStatus.TRIGGERED);
                        triggerRepo.save(trigger);
                        log.info("✅ Trigger {} converted to live trade and marked as TRIGGERED", trigger.getId());
                    } else {
                        log.warn("⚠️ Trigger {} execution skipped (likely due to missing LTP). Keeping request for retry.", trigger.getId());
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

                double tolerance = 1.006;

                if (ltp > trigger.getEntryPrice() * tolerance) {
                    log.warn("⚠️ Spot LTP {} is more than {}% above entry price {} for trigger {}. Marking as REJECTED.", ltp, (tolerance - 1) * 100, trigger.getEntryPrice(), trigger.getId());
                    trigger.setStatus(TriggeredTradeStatus.REJECTED);
                    triggerRepo.save(trigger);
                    continue;
                }

                boolean isPE = "PE".equalsIgnoreCase(trigger.getOptionType());
                boolean conditionMet;
                if (isPE) {
                    // For PE, trigger if spot price goes BELOW entry price
                    conditionMet = ltp <= trigger.getEntryPrice();
                    
                    // Check for gap down with tolerance for PE
                    double gapDownTolerance = 0.994; // 0.6% tolerance
                    if (ltp < trigger.getEntryPrice() * gapDownTolerance) {
                        log.warn("⚠️ Spot LTP {} is less than {}% below entry price {} for PE trigger {}. Marking as REJECTED.", ltp, (1 - gapDownTolerance) * 100, trigger.getEntryPrice(), trigger.getId());
                        trigger.setStatus(TriggeredTradeStatus.REJECTED);
                        triggerRepo.save(trigger);
                        continue;
                    }
                } else {
                    // For CE (or others), trigger if spot price goes ABOVE entry price
                    conditionMet = ltp >= trigger.getEntryPrice();
                }

                if (conditionMet) {
                    log.info("🚀 Spot Entry condition met for {} ({}) at SpotLTP: {}", trigger.getSymbol(), trigger.getOptionType(), ltp);

                    // convert request -> executed entity and run execution flow
                    TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(trigger);
                    
                    if (executed != null) {
                        trigger.setStatus(TriggeredTradeStatus.TRIGGERED);
                        triggerRepo.save(trigger);
                        log.info("✅ Trigger {} converted to live trade and marked as TRIGGERED", trigger.getId());
                    } else {
                        log.warn("⚠️ Trigger {} execution skipped (likely due to missing LTP). Keeping request for retry.", trigger.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error evaluating price trigger for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
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
                         // Try fetching from MStock if local cache is missing
                         try {
                             ScriptMasterEntity script = scriptMasterRepository.findByScripCode(trade.getScripCode());
                             if (script != null) {
                                 // Construct MStock instrument key, e.g., "NFO:BANKNIFTY23OCT44000CE"
                                 // Assuming script.getExchange() gives "NFO", "NSE", etc. and script.getTradingSymbol() gives the symbol
                                 // Adjust the format based on how MStock expects it. Usually "EXCHANGE:SYMBOL"
                                 String mstockKey = script.getExchange() + ":" + script.getTradingSymbol();
                                 Map<String, Object> mstockData = mStockLtpService.fetchLtpForInstrument(mstockKey);
                                 if (mstockData != null && mstockData.get("last_price") != null) {
                                     Object priceObj = mstockData.get("last_price");
                                     if (priceObj instanceof Number) {
                                         tradedLtp = ((Number) priceObj).doubleValue();
                                         log.info("Fetched missing LTP from MStock for {}: {}", mstockKey, tradedLtp);
                                     }
                                 }
                             }
                         } catch (Exception ex) {
                             log.warn("Failed to fetch fallback LTP from MStock for trade {}: {}", trade.getId(), ex.getMessage());
                         }
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

                // Only act if still in EXECUTED
                if (persisted.getStatus() != TriggeredTradeStatus.EXECUTED) return 0;

                // Determine effective prices for SL and Target
                Double slRefPrice = Boolean.TRUE.equals(persisted.getUseSpotForSl()) ? spotLtp : tradedLtp;
                Double targetRefPrice = Boolean.TRUE.equals(persisted.getUseSpotForTarget()) ? spotLtp : tradedLtp;

                Double slVal = persisted.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);
                
                // Check SL against effective reference price
                boolean slHit = false;
                if (slRefPrice != null && hasValidSl) {
                    boolean isSpotSl = Boolean.TRUE.equals(persisted.getUseSpotForSl());
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
                            int updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            if (updated == 0) {
                                updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.TARGET_ORDER_PLACED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            }
                            return updated;
                        }
                    } else {
                        // Standard target hit logic (any target hit -> exit all)
                        boolean isSpotTarget = Boolean.TRUE.equals(persisted.getUseSpotForTarget());
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
                Double slRefPrice = Boolean.TRUE.equals(reloaded.getUseSpotForSl()) ? spotLtp : tradedLtp;
                Double targetRefPrice = Boolean.TRUE.equals(reloaded.getUseSpotForTarget()) ? spotLtp : tradedLtp;

                String exitReason = reloaded.getExitReason();
                boolean exitOrderAlreadyPresent = reloaded.getExitOrderId() != null && !reloaded.getExitOrderId().isBlank();
                if ("STOP_LOSS_HIT".equals(exitReason)) {
                    Double stopPriceOption = reloaded.getStopLoss();
                    boolean usesSpotSl = Boolean.TRUE.equals(reloaded.getUseSpotForSl()) ||
                            (reloaded.getUseSpotForSl() == null && Boolean.TRUE.equals(reloaded.getUseSpotPrice()));

                    boolean modified = false;
                    if (exitOrderAlreadyPresent && !usesSpotSl && stopPriceOption != null && stopPriceOption > 0) {
                        modified = tradeExecutionService.modifyExitOrderForStop(reloaded, stopPriceOption);
                    }

                    if (modified) {
                        log.warn("📉 SL hit for trade {} - modified existing exit order {} to price {}", tradeId, reloaded.getExitOrderId(), stopPriceOption);
                    } else {
                        log.warn("📉 SL hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff", tradeId, slRefPrice, tradedLtp);
                        tradeExecutionService.squareOff(reloaded, tradedLtp, "STOP_LOSS_HIT");
                    }
                } else {
                    // TARGET_HIT
                    if (exitOrderAlreadyPresent) {
                        log.info("🎯 Target hit for trade {} - existing exit order {} already placed. Maintaining TARGET_ORDER_PLACED state.", tradeId, reloaded.getExitOrderId());
                        try {
                            reloaded.setStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
                            triggeredRepo.save(reloaded);
                        } catch (Exception e) {
                            log.debug("Failed to persist TARGET_ORDER_PLACED status for trade {}: {}", tradeId, e.getMessage());
                        }
                    } else if (Boolean.TRUE.equals(reloaded.getTslEnabled())) {
                        Integer lots = reloaded.getLots();
                        // If lots info is missing, assume single lot / full exit
                        if (lots == null || lots <= 1) {
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
        boolean isSpotTarget = Boolean.TRUE.equals(trade.getUseSpotForTarget());
        boolean isPE = "PE".equalsIgnoreCase(trade.getOptionType());
        
        boolean target1Hit, target2Hit, target3Hit;
        
        if (isSpotTarget && isPE) {
            // For PE with Spot Target: Hit if Spot Price goes BELOW Target
            target1Hit = trade.getTarget1() != null && trade.getTarget1() > 0d && ltp <= trade.getTarget1();
            target2Hit = trade.getTarget2() != null && trade.getTarget2() > 0d && ltp <= trade.getTarget2();
            target3Hit = trade.getTarget3() != null && trade.getTarget3() > 0d && ltp <= trade.getTarget3();
        } else {
            // For CE (or non-spot Target): Hit if Price goes ABOVE Target
            target1Hit = trade.getTarget1() != null && trade.getTarget1() > 0d && ltp >= trade.getTarget1();
            target2Hit = trade.getTarget2() != null && trade.getTarget2() > 0d && ltp >= trade.getTarget2();
            target3Hit = trade.getTarget3() != null && trade.getTarget3() > 0d && ltp >= trade.getTarget3();
        }

        if (!target1Hit && !target2Hit && !target3Hit) return 0;

        int currentLots = trade.getLots() != null ? trade.getLots() : 1;
        int totalLots = trade.getOriginalLots() != null ? trade.getOriginalLots() : currentLots;

        int lotsToBook = 0;
        
        if (totalLots == 3) {
            // 3 Lots: 1 @ T1, 1 @ T2, 1 @ T3
            if (target3Hit) {
                lotsToBook = currentLots;
            } else if (target2Hit) {
                // Should have 1 lot remaining (Lot 3)
                int desiredRemaining = 1;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                }
            } else if (target1Hit) {
                // Should have 2 lots remaining (Lot 2, Lot 3)
                int desiredRemaining = 2;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                }
            }
        } else if (totalLots == 2) {
            // 2 Lots: 1 @ T1, 1 @ T2 (Final)
            if (target2Hit) {
                lotsToBook = currentLots; // Close all
            } else if (target1Hit) {
                // Should have 1 lot remaining
                int desiredRemaining = 1;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                }
            }
        } else {
            // Mix: 40% @ T1, 40% @ T2, 20% @ T3
            int lot40 = (int) Math.round(totalLots * 0.4);
            if (lot40 < 1) lot40 = 1;

            int exitAtT1 = lot40;
            int exitAtT2 = lot40;
            int exitAtT3 = totalLots - exitAtT1 - exitAtT2;

            if (target3Hit) {
                lotsToBook = currentLots;
            } else if (target2Hit) {
                int desiredRemaining = exitAtT3;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                }
            } else if (target1Hit) {
                int desiredRemaining = exitAtT2 + exitAtT3;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                }
            }
        }
        
        // Safety cap
        if (lotsToBook > currentLots) lotsToBook = currentLots;
        
        return lotsToBook;
    }

    private void handlePartialBooking(TriggeredTradeSetupEntity trade, double referenceLtp, double tradedLtp, int currentLots) {
        // Re-calculate lotsToBook to determine split and new SL
        // (Logic duplicated from calculateLotsToBook but needed for newStopLoss determination)
        
        boolean isSpotTarget = Boolean.TRUE.equals(trade.getUseSpotForTarget());
        boolean isPE = "PE".equalsIgnoreCase(trade.getOptionType());
        
        boolean target1Hit, target2Hit, target3Hit;
        
        if (isSpotTarget && isPE) {
            // For PE with Spot Target: Hit if Spot Price goes BELOW Target
            target1Hit = trade.getTarget1() != null && trade.getTarget1() > 0d && referenceLtp <= trade.getTarget1();
            target2Hit = trade.getTarget2() != null && trade.getTarget2() > 0d && referenceLtp <= trade.getTarget2();
            target3Hit = trade.getTarget3() != null && trade.getTarget3() > 0d && referenceLtp <= trade.getTarget3();
        } else {
            // For CE (or non-spot Target): Hit if Price goes ABOVE Target
            target1Hit = trade.getTarget1() != null && trade.getTarget1() > 0d && referenceLtp >= trade.getTarget1();
            target2Hit = trade.getTarget2() != null && trade.getTarget2() > 0d && referenceLtp >= trade.getTarget2();
            target3Hit = trade.getTarget3() != null && trade.getTarget3() > 0d && referenceLtp >= trade.getTarget3();
        }

        int totalLots = trade.getOriginalLots() != null ? trade.getOriginalLots() : currentLots;
        if (trade.getOriginalLots() == null) {
            trade.setOriginalLots(totalLots);
            triggeredRepo.save(trade);
        }

        log.info("🎯 Target hit for trade {} with {} current lots (original: {}). Calculating partial booking.", trade.getId(), currentLots, totalLots);

        int lotsToBook = 0;
        double newStopLoss = trade.getStopLoss();

        if (totalLots == 3) {
            if (target3Hit) {
                lotsToBook = currentLots;
            } else if (target2Hit) {
                int desiredRemaining = 1;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                    newStopLoss = trade.getTarget1();
                }
            } else if (target1Hit) {
                int desiredRemaining = 2;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                    newStopLoss = trade.getEntryPrice();
                }
            }
        } else if (totalLots == 2) {
            if (target2Hit) {
                lotsToBook = currentLots;
            } else if (target1Hit) {
                int desiredRemaining = 1;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                    newStopLoss = trade.getEntryPrice();
                }
            }
        } else {
            int lot40 = (int) Math.round(totalLots * 0.4);
            if (lot40 < 1) lot40 = 1;
            int exitAtT1 = lot40;
            int exitAtT2 = lot40;
            int exitAtT3 = totalLots - exitAtT1 - exitAtT2;

            if (target3Hit) {
                lotsToBook = currentLots;
            } else if (target2Hit) {
                int desiredRemaining = exitAtT3;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                    newStopLoss = trade.getTarget1();
                }
            } else if (target1Hit) {
                int desiredRemaining = exitAtT2 + exitAtT3;
                if (currentLots > desiredRemaining) {
                    lotsToBook = currentLots - desiredRemaining;
                    newStopLoss = trade.getEntryPrice();
                }
            }
        }

        if (lotsToBook > currentLots) lotsToBook = currentLots;
        if (lotsToBook <= 0) lotsToBook = currentLots; // Fallback

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
            remainingTrade.setStopLoss(newStopLoss); // Updated SL
            remainingTrade.setTarget1(trade.getTarget1());
            remainingTrade.setTarget2(trade.getTarget2());
            remainingTrade.setTarget3(trade.getTarget3());
            remainingTrade.setTrailingSl(trade.getTrailingSl());
            remainingTrade.setQuantity(remainingQty);
            remainingTrade.setLots(remainingLots);
            remainingTrade.setOriginalLots(totalLots);
            remainingTrade.setTslEnabled(trade.getTslEnabled()); // Preserve TSL flag
            remainingTrade.setIntraday(trade.getIntraday());
            remainingTrade.setStatus(TriggeredTradeStatus.EXECUTED);
            remainingTrade.setTriggeredAt(trade.getTriggeredAt());
            remainingTrade.setEntryAt(trade.getEntryAt());
            remainingTrade.setUseSpotForEntry(trade.getUseSpotForEntry());
            remainingTrade.setUseSpotForSl(trade.getUseSpotForSl());
            remainingTrade.setUseSpotForTarget(trade.getUseSpotForTarget());
            remainingTrade.setSpotScripCode(trade.getSpotScripCode());
            
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
                    webSocketSubscriptionService.subscribeToScrip(spotKey);
                }
            }

            // Proceed to square off this portion
            tradeExecutionService.squareOff(trade, tradedLtp, "TARGET_HIT_PARTIAL");
        }
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

            Double entryPriceForPnl = saved.getActualEntryPrice();
            if (entryPriceForPnl == null) {
                entryPriceForPnl = saved.getEntryPrice();
            }
            Long quantity = saved.getQuantity();

            if (entryPriceForPnl == null || quantity == null || quantity <= 0) {
                log.debug("Cannot compute PnL for trade {} - missing entryPrice/quantity", saved.getId());
                return;
            }

            Double exitPrice = saved.getExitPrice();
            if (exitPrice == null) exitPrice = ltp;

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
}
