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
import org.springframework.transaction.annotation.Transactional;

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

                    tradeExecutionService.execute(trigger, ltp); // Places order + persists in live trades
                    triggerRepo.deleteById(trigger.getId());

                    log.info("✅ Trigger {} converted to live trade and removed from request table", trigger.getId());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error evaluating price trigger for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    public void monitorOpenTrades(Integer scripCode, double ltp) {
        try {
            log.debug("Invoked monitorOpenTrades for scripCode={} with ltp={}", scripCode, ltp);
            List<TriggeredTradeSetupEntity> trades = triggeredRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.EXECUTED
            );

            for (TriggeredTradeSetupEntity trade : trades) {
                // process each trade under a DB lock to avoid races across threads/processes
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

    @Transactional
    protected void handleTradeWithLock(Long tradeId, double ltp) {
        // Acquire a pessimistic write lock on the row to prevent concurrent processors from seeing the same state.
        TriggeredTradeSetupEntity persisted = entityManager.find(TriggeredTradeSetupEntity.class, tradeId, LockModeType.PESSIMISTIC_WRITE);
        if (persisted == null) return;

        // Only act if trade is still in EXECUTED state
        if (persisted.getStatus() != TriggeredTradeStatus.EXECUTED) {
            log.debug("Trade {} status changed to {} - skipping monitor", tradeId, persisted.getStatus());
            return;
        }

        Double slVal = persisted.getStopLoss();
        boolean hasValidSl = (slVal != null && slVal > 0d);
        boolean slHit = hasValidSl && (ltp <= slVal);

        boolean targetHit = Stream.of(persisted.getTarget1(), persisted.getTarget2(), persisted.getTarget3())
                .filter(Objects::nonNull)
                .filter(t -> t > 0d)
                .anyMatch(target -> ltp >= target);

        if (slHit) {
            // Transition EXECUTED -> EXIT_TRIGGERED under the same transaction/lock
            int claimed = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "SL_OR_TARGET_HIT");
            if (claimed == 1) {
                log.warn("📉 SL hit for trade {} (SL={}) at LTP: {} - claim succeeded, initiating exit", tradeId, slVal, ltp);
                // Refresh entity state
                persisted = entityManager.find(TriggeredTradeSetupEntity.class, tradeId);
                tradeExecutionService.squareOff(persisted, ltp, "STOP_LOSS_HIT");
                persistPnlIfMissing(persisted, ltp);
            } else {
                log.info("📉 SL condition observed for trade {} at LTP: {} but another process claimed exit (claim={}) - skipping", tradeId, ltp, claimed);
            }
        } else if (targetHit) {
            int claimed = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "SL_OR_TARGET_HIT");
            if (claimed == 1) {
                log.info("🎯 Target hit for trade {} at LTP: {} - claim succeeded, initiating exit", tradeId, ltp);
                persisted = entityManager.find(TriggeredTradeSetupEntity.class, tradeId);
                tradeExecutionService.squareOff(persisted, ltp, "TARGET_HIT");
                persistPnlIfMissing(persisted, ltp);
            } else {
                log.info("🎯 Target condition observed for trade {} at LTP: {} but another process claimed exit (claim={}) - skipping", tradeId, ltp, claimed);
            }
        } else {
            log.debug("No SL/Target hit for trade {} at LTP: {} (SL: {}, Targets: [{}, {}, {}])",
                    tradeId, ltp, persisted.getStopLoss(), persisted.getTarget1(), persisted.getTarget2(), persisted.getTarget3());
        }
    }

    // If squareOff did not save pnl/exit, compute and persist here as a fallback
    private void persistPnlIfMissing(TriggeredTradeSetupEntity originalTrade, double ltp) {
        try {
            if (originalTrade == null || originalTrade.getId() == null) return;

            var opt = triggeredRepo.findById(originalTrade.getId());
            if (!opt.isPresent()) return;

            TriggeredTradeSetupEntity saved = opt.get();

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
            // Persist exitPrice only if it wasn't already set by squareOff
            if (saved.getExitPrice() == null) saved.setExitPrice(exitPrice);
             // If status wasn't updated by squareOff, mark exited success
             if (saved.getStatus() == TriggeredTradeStatus.EXECUTED) {
                 saved.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
             }

             triggeredRepo.save(saved);
             log.info("💾 Persisted PnL {} for trade {}", saved.getPnl(), saved.getId());
        } catch (Exception e) {
            log.error("❌ Error saving PnL for trade {}: {}", originalTrade == null ? "null" : originalTrade.getId(), e.getMessage(), e);
        }
    }
}
