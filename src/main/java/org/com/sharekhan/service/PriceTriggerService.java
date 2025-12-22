package org.com.sharekhan.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceTriggerService {

    private final TriggerTradeRequestRepository triggerRepo;
    private final TriggeredTradeSetupRepository triggeredRepo;
    private final TradeExecutionService tradeExecutionService;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    public void evaluatePriceTrigger(Integer scripCode, double ltp) {
        try {
            List<TriggerTradeRequestEntity> candidates = triggerRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
            );

            for (TriggerTradeRequestEntity trigger : candidates) {
                if (ltp >= trigger.getEntryPrice()) {
                    log.info("🚀 Entry condition met for {} at LTP: {}", trigger.getSymbol(), ltp);

                    // convert request -> executed entity and run execution flow
                    tradeExecutionService.executeTradeFromEntity(trigger);
                    triggerRepo.deleteById(trigger.getId());

                    log.info("✅ Trigger {} converted to live trade and removed from request table", trigger.getId());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error evaluating price trigger for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void monitorOpenTrades(Integer scripCode, double ltp) {
        try {
            log.debug("Invoked monitorOpenTrades for scripCode={} with ltp={}", scripCode, ltp);
            List<TriggeredTradeSetupEntity> trades = triggeredRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.EXECUTED
            );

            for (TriggeredTradeSetupEntity trade : trades) {
                // process each trade but perform claim inside a short transaction to avoid long locks
                try {
                    handleTradeWithLock(trade.getId(), ltp);
                } catch (Exception e) {
                    log.error("❌ Error handling trade {} in monitor: {}", trade.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("❌ Error monitoring open trades for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    // NOTE: this method no longer uses @Transactional; it uses a TransactionTemplate to perform a short atomic claim
    protected void handleTradeWithLock(Long tradeId, double ltp) {
        try {
            final TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            // Execute quick transaction: read the current trade and attempt atomic claim if condition met
            Integer claimed = txTemplate.execute(status -> {
                var opt = triggeredRepo.findById(tradeId);
                if (opt.isEmpty()) return 0;
                TriggeredTradeSetupEntity persisted = opt.get();

                // Only act if still in EXECUTED
                if (persisted.getStatus() != TriggeredTradeStatus.EXECUTED) return 0;

                Double slVal = persisted.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);
                boolean slHit = hasValidSl && (ltp <= slVal);

                boolean targetHit = Stream.of(persisted.getTarget1(), persisted.getTarget2(), persisted.getTarget3())
                        .filter(Objects::nonNull)
                        .filter(t -> t > 0d)
                        .anyMatch(target -> ltp >= target);

                if (!slHit && !targetHit) return 0; // nothing to do

                // Attempt atomic transition EXECUTED -> EXIT_TRIGGERED
                int res = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "SL_OR_TARGET_HIT");
                return res;
            });

            if (claimed != null && claimed == 1) {
                // Claim succeeded — now re-load the entity (outside the short transaction) and proceed to squareOff
                TriggeredTradeSetupEntity reloaded = triggeredRepo.findById(tradeId).orElseThrow(() -> new RuntimeException("Trade not found after claim: " + tradeId));

                // Determine whether it was SL or target by comparing ltp with reloaded values (best-effort)
                Double slVal = reloaded.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);
                boolean slHit = hasValidSl && (ltp <= slVal);

                if (slHit) {
                    log.warn("📉 SL hit for trade {} at LTP: {} - proceeding to squareOff", tradeId, ltp);
                    tradeExecutionService.squareOff(reloaded, ltp, "STOP_LOSS_HIT");
                } else {
                    log.info("🎯 Target hit for trade {} at LTP: {} - proceeding to squareOff", tradeId, ltp);
                    tradeExecutionService.squareOff(reloaded, ltp, "TARGET_HIT");
                }

                // ensure pnl persisted if needed
                persistPnlIfMissing(reloaded, ltp);
            } else {
                log.debug("No claim performed for trade {} (claimed={})", tradeId, claimed);
            }
        } catch (Exception e) {
            log.error("❌ Error in handleTradeWithLock for trade {}: {}", tradeId, e.getMessage(), e);
        }
    }

    // If squareOff did not save pnl/exit, compute and persist here as a fallback
    private void persistPnlIfMissing(TriggeredTradeSetupEntity originalTrade, double ltp) {
        try {
            if (originalTrade == null || originalTrade.getId() == null) return;

            var opt = triggeredRepo.findById(originalTrade.getId());
            if (opt.isEmpty()) return;

            TriggeredTradeSetupEntity saved = opt.get();

            // If an exit order was placed (we have an exitOrderId) then the final status/exitPrice
            // must be determined by order status polling. Do not attempt to compute/mark EXITED here.
            if (saved.getExitOrderId() != null && !saved.getExitOrderId().isBlank()) {
                log.debug("Skipping persistPnlIfMissing for trade {} because exitOrderId={} is present; order polling will update status.", saved.getId(), saved.getExitOrderId());
                return;
            }

            // If PnL already present, assume squareOff handled persistence.
            if (saved.getPnl() != null) return;

            Double entryPrice = saved.getEntryPrice();
            Long quantity = saved.getQuantity();

            if (entryPrice == null || quantity == null || quantity <= 0) {
                log.debug("Cannot compute PnL for trade {} - missing entryPrice/quantity", saved.getId());
                return;
            }

            Double exitPrice = saved.getExitPrice();
            if (exitPrice == null) exitPrice = ltp;

            try {
                java.math.BigDecimal exitBd = java.math.BigDecimal.valueOf(exitPrice);
                java.math.BigDecimal entryBd = java.math.BigDecimal.valueOf(entryPrice);
                java.math.BigDecimal qtyBd = java.math.BigDecimal.valueOf(quantity);
                java.math.BigDecimal rawPnlBd = exitBd.subtract(entryBd).multiply(qtyBd).setScale(2, java.math.RoundingMode.HALF_UP);
                saved.setPnl(rawPnlBd.doubleValue());
            } catch (Exception e) {
                log.warn("Failed computing PnL in persistPnlIfMissing for trade {}: {}", saved.getId(), e.getMessage());
                return;
            }
            // Persist exitPrice and mark as exited success to stop further monitoring
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
