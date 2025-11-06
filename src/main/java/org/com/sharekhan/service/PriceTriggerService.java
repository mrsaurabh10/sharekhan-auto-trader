package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceTriggerService {

    private final TriggerTradeRequestRepository triggerRepo;
    private final TriggeredTradeSetupRepository triggeredRepo;
    private final TradeExecutionService tradeExecutionService;

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
            Optional<TriggeredTradeSetupEntity> trades = triggeredRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.EXECUTED
            );

            //for (TriggeredTradeSetupEntity trade : trades)
            if(trades.isPresent())
            {
                TriggeredTradeSetupEntity trade = trades.get();
                // Defensive: do not treat null or non-positive stopLoss as a valid SL
                Double slVal = trade.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);
                boolean slHit = hasValidSl && (ltp <= slVal);

                // Targets: consider only non-null and >0 targets
                boolean targetHit = Stream.of(trade.getTarget1(), trade.getTarget2(), trade.getTarget3())
                        .filter(Objects::nonNull)
                        .filter(t -> t > 0d)
                        .anyMatch(target -> ltp >= target);

                if (slHit) {
                    log.warn("📉 SL hit for trade {} (SL={}) at LTP: {}", trade.getId(), slVal, ltp);
                    tradeExecutionService.squareOff(trade, ltp, "STOP_LOSS_HIT");
                    // ensure pnl/exit saved if squareOff didn't persist them
                    persistPnlIfMissing(trade, ltp);
                } else if (targetHit) {
                    log.info("🎯 Target hit for trade {} at LTP: {}", trade.getId(), ltp);
                    tradeExecutionService.squareOff(trade, ltp, "TARGET_HIT");
                    // ensure pnl/exit saved if squareOff didn't persist them
                    persistPnlIfMissing(trade, ltp);
                } else {
                    log.debug("No SL/Target hit for trade {} at LTP: {} (SL: {}, Targets: [{}, {}, {}])",
                            trade.getId(), ltp, slVal, trade.getTarget1(), trade.getTarget2(), trade.getTarget3());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error monitoring open trades for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    // If squareOff did not save pnl/exit, compute and persist here as a fallback
    private void persistPnlIfMissing(TriggeredTradeSetupEntity originalTrade, double ltp) {
        try {
            if (originalTrade == null || originalTrade.getId() == null) return;

            Optional<TriggeredTradeSetupEntity> opt = triggeredRepo.findById(originalTrade.getId());
            if (!opt.isPresent()) return;

            TriggeredTradeSetupEntity saved = opt.get();

            // If PnL already present, assume squareOff handled persistence.
            if (saved.getPnl() != null) return;

            Double entryPrice = saved.getEntryPrice();
            Integer quantity = saved.getQuantity();

            if (entryPrice == null || quantity == null || quantity <= 0) {
                log.debug("Cannot compute PnL for trade {} - missing entryPrice/quantity", saved.getId());
                return;
            }

            // Prefer the actual exitPrice saved by squareOff; otherwise fall back to the incoming ltp
            Double exitPrice = saved.getExitPrice();
            if (exitPrice == null) exitPrice = ltp;
            double rawPnl = (exitPrice - entryPrice) * quantity;
            BigDecimal bd = BigDecimal.valueOf(rawPnl).setScale(2, RoundingMode.HALF_UP);

            saved.setPnl(bd.doubleValue());
            // Persist exitPrice only if it wasn't already set by squareOff
            if (saved.getExitPrice() == null) saved.setExitPrice(exitPrice);
            // If status wasn't updated by squareOff, mark exited success
            if (saved.getStatus() == TriggeredTradeStatus.EXECUTED) {
                saved.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
            }

            triggeredRepo.save(saved);
            log.info("💾 Persisted PnL {} for trade {}", bd, saved.getId());
        } catch (Exception e) {
            log.error("❌ Error saving PnL for trade {}: {}", originalTrade == null ? "null" : originalTrade.getId(), e.getMessage(), e);
        }
    }
}
