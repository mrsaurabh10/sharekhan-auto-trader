package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;

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
                boolean slHit = ltp <= trade.getStopLoss();
                boolean targetHit = Stream.of(trade.getTarget1(), trade.getTarget2(), trade.getTarget3())
                        .filter(Objects::nonNull)
                        .anyMatch(target -> ltp >= target);

                if (slHit) {
                    log.warn("📉 SL hit for trade {} at LTP: {}", trade.getId(), ltp);
                    tradeExecutionService.squareOff(trade, ltp, "STOP_LOSS_HIT");
                } else if (targetHit) {
                    log.info("🎯 Target hit for trade {} at LTP: {}", trade.getId(), ltp);
                    tradeExecutionService.squareOff(trade, ltp, "TARGET_HIT");
                }
            }
        } catch (Exception e) {
            log.error("❌ Error monitoring open trades for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }
}
