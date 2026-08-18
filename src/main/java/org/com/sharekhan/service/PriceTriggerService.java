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
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceTriggerService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    /**
     * Live price-trigger entries must not evaluate during the initial five minutes
     * of the equity session, when opening volatility can produce false triggers.
     */
    private static final LocalTime ENTRY_EVALUATION_START = LocalTime.of(9, 20);
    /**
     * Intraday entries must stop before the square-off workflow.  The exchange
     * may remain open until 15:30, but opening a new intraday position after
     * 15:20 leaves too little time to exit it safely.
     */
    private static final LocalTime INTRADAY_ENTRY_CUTOFF = LocalTime.of(15, 20);
    private static final LocalTime OPENING_RULE_CUTOFF = LocalTime.of(9, 30);
    private static final String ATR_SIGNAL_SOURCE = "atr-signal";
    private static final double ATR_TARGET1_PROXIMITY_FRACTION = 0.10d;
    private static final String ATR_PREVIOUS_DAY_SOURCE = "atr-pdh-pdl-strategy";
    private static final double ATR_PREVIOUS_DAY_MAX_LOSS_PER_LOT = 3000d;
    private static final String GAP_FILL_EXIT_REASON = "GAP_FILL_STOP";
    private static final int CLAIM_NONE = 0;
    private static final int CLAIM_EXIT_TRIGGERED = 1;
    private static final int CLAIM_RECOVER_UNPLACED_EXIT = 2;
    /**
     * EXIT_TRIGGERED is an ownership state, not an invitation for another tick
     * worker to repeat the partial-booking flow.  Only recover a genuinely
     * abandoned claim after this grace period.
     */
    private static final Duration EXIT_RECOVERY_GRACE_PERIOD = Duration.ofSeconds(30);
    private static final List<TriggeredTradeStatus> MONITORABLE_TRADE_STATUSES = List.of(
            TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED,
            TriggeredTradeStatus.EXIT_TRIGGERED
    );
    private final ConcurrentMap<Long, LocalDateTime> gapPolicyAttemptedAt = new ConcurrentHashMap<>();

    private final TriggerTradeRequestRepository triggerRepo;
    private final TriggeredTradeSetupRepository triggeredRepo;
    private final TradeExecutionService tradeExecutionService;
    private final PlatformTransactionManager transactionManager;
    private final ScriptMasterRepository scriptMasterRepository;
    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final LtpCacheService ltpCacheService;
    private final MStockLtpService mStockLtpService;
    private final MStockIntradayCandleService mStockIntradayCandleService;
    private final MStockInstrumentResolver instrumentResolver;
    private final SharekhanHistoricalService sharekhanHistoricalService;
    private final ScripExecutorManager scripExecutorManager;
    private final OrderExecutionDispatcher orderExecutionDispatcher;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    /** Optional while Shoonya is being rolled out; existing trigger processing remains available. */
    @Autowired(required = false)
    private ShoonyaQuoteService shoonyaQuoteService;

    public void evaluatePriceTrigger(Integer scripCode, double ltp) {
        LocalDateTime nowIst = nowIst();
        if (!isIntradayEntryWindowOpen(nowIst)) {
            log.debug("Skipping price trigger evaluation outside the intraday entry window: {} IST", nowIst);
            return;
        }

        try {
            // 1. Check triggers where scripCode is the TRADED instrument
            List<TriggerTradeRequestEntity> candidates = triggerRepo.findByScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
            );

            for (TriggerTradeRequestEntity trigger : candidates) {
                if (entryExecutionOwnsOrHasPersistedTrade(trigger)) {
                    continue;
                }
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
                    int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
                    if (claimed == 1) {
                        String conditionSummary = String.format("option LTP %.2f >= entry %.2f", ltp, trigger.getEntryPrice());
                        TradeEventLogger.logEntryTriggered(trigger, ltp, "OPTION_LTP", conditionSummary);
                        log.info("🚀 Entry condition met for {} at LTP: {}", trigger.getSymbol(), ltp);

                        // convert request -> executed entity and run execution flow
                        trigger.setStatus(TriggeredTradeStatus.ENTRY_SUBMITTING);
                        dispatchEntryExecution(trigger);
                    }
                }
            }

            // 2. Check triggers where scripCode is the SPOT instrument
            List<TriggerTradeRequestEntity> spotCandidates = triggerRepo.findBySpotScripCodeAndStatus(
                    scripCode, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
            );

            for (TriggerTradeRequestEntity trigger : spotCandidates) {
                if (entryExecutionOwnsOrHasPersistedTrade(trigger)) {
                    continue;
                }
                // Check if entry is based on spot price
                boolean isSpotEntry = Boolean.TRUE.equals(trigger.getUseSpotForEntry()) 
                        || (trigger.getUseSpotForEntry() == null && Boolean.TRUE.equals(trigger.getUseSpotPrice()));

                // Only process if entry is based on spot
                if (!isSpotEntry) {
                    continue;
                }
                
                if (trigger.getEntryPrice() == null) continue;

                initializeGapPolicy(trigger);

                OpeningDecision openingDecision = evaluateAtrSpotEntryRule(trigger, nowIst, ltp);
                if (openingDecision == OpeningDecision.WAIT || openingDecision == OpeningDecision.REJECTED) {
                    continue;
                }

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

                if (conditionMet || openingDecision == OpeningDecision.READY) {
                    int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(), TriggeredTradeStatus.ENTRY_SUBMITTING.name());
                    if (claimed == 1) {
                        String conditionSummary = openingDecision == OpeningDecision.READY
                                ? "directional one-minute close confirmed and current spot remains beyond entry"
                                : isPE
                                    ? String.format("spot LTP %.2f <= entry %.2f", ltp, entryPrice)
                                    : String.format("spot LTP %.2f >= entry %.2f", ltp, entryPrice);
                        TradeEventLogger.logEntryTriggered(trigger, ltp, "SPOT_LTP", conditionSummary);
                        log.info("🚀 Spot Entry condition met for {} ({}) at SpotLTP: {}", trigger.getSymbol(), trigger.getOptionType(), ltp);

                        // convert request -> executed entity and run execution flow
                        trigger.setStatus(TriggeredTradeStatus.ENTRY_SUBMITTING);
                        dispatchEntryExecution(trigger);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error evaluating price trigger for scripCode {}: {}", scripCode, e.getMessage(), e);
        }
    }

    /**
     * Re-evaluates only one active, option-target trade after its TSL flag is
     * edited. This intentionally avoids the general tick evaluator because that
     * would also consider every pending request for the same option scrip.
     */
    public void reEvaluateOptionTradeAfterTslChange(Long tradeId) {
        if (tradeId == null) {
            return;
        }
        TriggeredTradeSetupEntity trade = triggeredRepo.findById(tradeId).orElse(null);
        if (trade == null || trade.getScripCode() == null || usesSpotForTarget(trade)) {
            return;
        }
        Double optionLtp = ltpCacheService.getLtp(trade.getScripCode());
        if (optionLtp == null || !Double.isFinite(optionLtp) || optionLtp <= 0d) {
            log.debug("TSL change for trade {} will be evaluated on the next option tick; no cached LTP is available.", tradeId);
            return;
        }
        Double spotLtp = trade.getSpotScripCode() == null ? null : ltpCacheService.getLtp(trade.getSpotScripCode());
        handleTradeWithLock(tradeId, optionLtp, spotLtp);
    }

    private void dispatchEntryExecution(TriggerTradeRequestEntity request) {
        if (request == null || request.getId() == null) {
            return;
        }
        Long requestId = request.getId();
        String key = orderExecutionKey("ENTRY:" + requestId, request.getBrokerCredentialsId());
        if (!orderExecutionDispatcher.submit(key, () -> executeTriggeredRequest(request))) {
            log.debug("Entry execution already queued/running for request {}", requestId);
        }
    }

    private void executeTriggeredRequest(TriggerTradeRequestEntity request) {
        if (request == null || request.getId() == null || request.getStatus() != TriggeredTradeStatus.ENTRY_SUBMITTING) {
            return;
        }
        Long requestId = request.getId();

        // A prior-day ATR CE must always stop below its spot entry (and PE above).
        // Refuse malformed risk geometry before it can reach the broker.  This
        // prevents a BDL-type inverted/near-entry stop from creating an order.
        if (isAtrPreviousDaySource(request) && !hasValidAtrPreviousDayRiskGeometry(request)) {
            triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(), TriggeredTradeStatus.REJECTED.name(),
                    "INVALID_ATR_RISK_LEVELS", "ATR prior-day stop must be below CE entry and above PE entry.");
            log.error("Rejected malformed ATR prior-day request {} for {}: optionType={} entry={} stopLoss={}",
                    requestId, request.getSymbol(), request.getOptionType(), request.getEntryPrice(), request.getStopLoss());
            return;
        }

        // A directional ATR prior-day setup may enter once, then re-enter once
        // only after the strategy has built a fresh five-minute structure.
        if (isAtrPreviousDaySource(request) && request.getAppUserId() != null
                && triggeredRepo.findTriggeredForSymbolOptionTypeOnDay(ATR_PREVIOUS_DAY_SOURCE, request.getSymbol(),
                request.getOptionType(), request.getAppUserId(), nowIst().toLocalDate().atStartOfDay(),
                nowIst().toLocalDate().plusDays(1).atStartOfDay()).size() >= 2) {
            triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(), TriggeredTradeStatus.CANCELLED.name(),
                    "DAILY_REENTRY_LIMIT_REACHED", "The one allowed ATR prior-day re-entry for this direction has already executed today.");
            log.info("ATR prior-day request {} for {} cancelled: re-entry limit reached",
                    requestId, request.getSymbol());
            return;
        }

        // ENTRY_SUBMITTING was atomically persisted before this work was queued.
        // It is deliberately not a triggerable state: a restart or slow broker
        // response must never submit the same request a second time.
        List<TriggeredTradeSetupEntity> existing = triggeredRepo.findByTriggerRequestId(requestId);
        if (existing != null && !existing.isEmpty()
                && !isOpeningSpreadRetryEligible(request, existing.get(existing.size() - 1))) {
            TriggeredTradeSetupEntity latest = existing.get(existing.size() - 1);
            TriggeredTradeStatus status = latest.getStatus() == TriggeredTradeStatus.EXECUTED
                    ? TriggeredTradeStatus.EXECUTED
                    : latest.getStatus() == TriggeredTradeStatus.REJECTED
                    ? TriggeredTradeStatus.REJECTED
                    : TriggeredTradeStatus.ENTRY_SUBMITTING;
            triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(),
                    status.name(), latest.getReason(), latest.getComment());
            log.info("Entry request {} already has persisted trade {}; not placing a duplicate order.", requestId, latest.getId());
            return;
        }

        TriggeredTradeSetupEntity executed = tradeExecutionService.executeTradeFromEntity(request);
        if (executed == null) {
            triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(),
                    TriggeredTradeStatus.FAILED.name(),
                    "ENTRY_EXECUTION_UNAVAILABLE",
                    "No trade record was created after the entry submission was claimed; retry is blocked to prevent a duplicate broker order.");
            log.error("Trigger {} did not produce an order; marked FAILED to prevent an unsafe duplicate submission.", requestId);
            return;
        }

        if (isOpeningSpreadRetryEligible(request, executed)) {
            int rearmed = triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                    TriggeredTradeStatus.ENTRY_SUBMITTING.name(), TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                    "OPENING_SPREAD_RETRY", "Opening option spread was too wide; retaining this original ATR request until 09:30 IST.");
            if (rearmed == 1) {
                log.info("ATR prior-day request {} retained for bounded opening spread retry", requestId);
            }
            return;
        }
        TriggeredTradeStatus status = executed.getStatus() == TriggeredTradeStatus.EXECUTED
                ? TriggeredTradeStatus.EXECUTED
                : executed.getStatus() == TriggeredTradeStatus.REJECTED
                ? TriggeredTradeStatus.REJECTED
                : TriggeredTradeStatus.ENTRY_SUBMITTING;
        triggerRepo.claimIfStatusEqualsWithOutcome(requestId,
                TriggeredTradeStatus.ENTRY_SUBMITTING.name(),
                status.name(), executed.getReason(), executed.getComment());
        log.info("Trigger {} execution completed with trade {} status={}", requestId, executed.getId(), status);
    }

    private boolean isOpeningSpreadRetryEligible(TriggerTradeRequestEntity request, TriggeredTradeSetupEntity trade) {
        if (!isAtrPreviousDaySource(request) || trade == null || trade.getStatus() != TriggeredTradeStatus.REJECTED
                || !nowIst().toLocalTime().isBefore(LocalTime.of(9, 30))) {
            return false;
        }
        String reason = trade.getReason();
        return "ENTRY_SPREAD_HARD_LIMIT_EXCEEDED".equals(reason)
                || "ENTRY_SPREAD_PERSISTENTLY_WIDE".equals(reason);
    }

    /**
     * Reconcile incomplete entry submissions.  A request that may already have
     * reached the broker is never rearmed automatically: without a persisted order
     * identifier we cannot prove that submitting it again is safe.
     */
    @Scheduled(fixedDelayString = "${app.trading.trigger-recovery-delay-ms:15000}")
    public void recoverStaleTriggeredRequests() {
        if (!isIntradayEntryWindowOpen(nowIst())) {
            log.debug("Skipping triggered-request recovery outside the intraday entry window.");
            return;
        }
        recoverIncompleteEntrySubmissions(TriggeredTradeStatus.TRIGGERED);
        recoverIncompleteEntrySubmissions(TriggeredTradeStatus.ENTRY_SUBMITTING);
    }

    private void recoverIncompleteEntrySubmissions(TriggeredTradeStatus incompleteStatus) {
        for (TriggerTradeRequestEntity request : triggerRepo.findByStatus(incompleteStatus)) {
            if (request.getId() == null || orderExecutionDispatcher.isInFlight(
                    orderExecutionKey("ENTRY:" + request.getId(), request.getBrokerCredentialsId()))) {
                continue;
            }
            List<TriggeredTradeSetupEntity> existing = triggeredRepo.findByTriggerRequestId(request.getId());
            if (existing != null && !existing.isEmpty()) {
                TriggeredTradeSetupEntity latest = existing.get(existing.size() - 1);
                TriggeredTradeStatus status = latest.getStatus() == TriggeredTradeStatus.EXECUTED
                        ? TriggeredTradeStatus.EXECUTED
                        : latest.getStatus() == TriggeredTradeStatus.REJECTED
                        ? TriggeredTradeStatus.REJECTED
                        : TriggeredTradeStatus.ENTRY_SUBMITTING;
                triggerRepo.claimIfStatusEquals(request.getId(), incompleteStatus.name(), status.name());
                continue;
            }
            int failed = triggerRepo.claimIfStatusEqualsWithOutcome(request.getId(),
                    incompleteStatus.name(),
                    TriggeredTradeStatus.FAILED.name(),
                    "ENTRY_SUBMISSION_STATE_UNKNOWN",
                    "Recovery found no persisted trade after an incomplete entry submission; retry is blocked because broker submission state cannot be proven.");
            if (failed == 1) {
                log.error("Marked incomplete entry request {} as FAILED; broker submission state is unknown and was not rearmed.", request.getId());
            }
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

        // This guard applies only to ATR spot-entry requests. Other manual and
        // strategy requests can legitimately use their own price geometry.
        if (!isAtrSource(trigger.getSource())) {
            return false;
        }

        boolean atrSpotEntry = isAtrSource(trigger.getSource())
                && (Boolean.TRUE.equals(trigger.getUseSpotForEntry())
                    || (trigger.getUseSpotForEntry() == null && Boolean.TRUE.equals(trigger.getUseSpotPrice())));

        Optional<ReferencePrice> openPrice = getTodayOpenReferencePrice(trigger, referenceScrip);
        if (openPrice.isPresent()) {
            ReferencePrice open = openPrice.get();
            if (isComparableToEntryPrice(open.price(), entryPrice)) {
                if (atrSpotEntry
                        ? rejectIfTargetAlreadyReached(trigger, open.label(), open.price(), downsideEntry)
                        : rejectIfReferencePriceInvalid(
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

        if (atrSpotEntry) {
            return rejectIfTargetAlreadyReached(trigger, currentPriceLabel, currentPrice, downsideEntry);
        }

        return rejectIfReferencePriceInvalid(
                trigger,
                currentPriceLabel,
                currentPrice,
                entryPrice,
                toleranceMultiplier,
                downsideEntry);
    }

    private boolean rejectIfTargetAlreadyReached(TriggerTradeRequestEntity trigger,
                                                  String priceLabel,
                                                  double referencePrice,
                                                  boolean downsideEntry) {
        Double target1 = trigger.getTarget1();
        if (target1 == null || target1 <= 0d || !Double.isFinite(referencePrice)) {
            return false;
        }
        Double entryPrice = trigger.getEntryPrice();
        double entryToTargetDistance = entryPrice == null ? 0d : Math.abs(target1 - entryPrice);
        double proximityBuffer = entryToTargetDistance * ATR_TARGET1_PROXIMITY_FRACTION;
        boolean reached = downsideEntry ? referencePrice <= target1 : referencePrice >= target1;
        boolean withinBuffer = proximityBuffer > 0d
                && (downsideEntry ? referencePrice <= target1 + proximityBuffer : referencePrice >= target1 - proximityBuffer);
        if (!reached && !withinBuffer) {
            return false;
        }
        int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.REJECTED.name());
        if (claimed == 1) {
            log.warn("{} {} has {} ATR target1 {} for trigger {} (T1 buffer={}). Marking as REJECTED.",
                    priceLabel, referencePrice, reached ? "reached/breached" : "entered the proximity buffer for",
                    target1, trigger.getId(), proximityBuffer);
        }
        return true;
    }

    private OpeningDecision evaluateAtrSpotEntryRule(TriggerTradeRequestEntity trigger,
                                                     LocalDateTime nowIst,
                                                     double currentSpotPrice) {
        if (trigger == null || !isAtrSource(trigger.getSource())) {
            return OpeningDecision.NORMAL;
        }
        Integer spotScripCode = trigger.getSpotScripCode();
        if (spotScripCode == null) {
            return OpeningDecision.WAIT;
        }
        LocalDateTime expectedCompletedMinute = nowIst.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        MStockIntradayCandleService.IntradayCandle apiCandle;
        try {
            apiCandle = mStockIntradayCandleService.getCompletedMinuteCandle(
                    spotScripCode, expectedCompletedMinute);
        } catch (Exception ex) {
            log.warn("Waiting for MStock ATR entry candle for request {} minute={}: {}",
                    trigger.getId(), expectedCompletedMinute, ex.getMessage());
            return OpeningDecision.WAIT;
        }
        if (apiCandle == null) {
            log.debug("Waiting for MStock ATR entry candle for request {}. expectedMinute={}",
                    trigger.getId(), expectedCompletedMinute);
            return OpeningDecision.WAIT;
        }
        LocalDateTime candleMinute = LocalDateTime.of(apiCandle.date(), apiCandle.time());
        if (!expectedCompletedMinute.equals(candleMinute)) {
            log.debug("Ignoring stale MStock ATR entry candle for request {}. expectedMinute={} actualMinute={}",
                    trigger.getId(), expectedCompletedMinute, candleMinute);
            return OpeningDecision.WAIT;
        }
        LtpCacheService.MinuteCandle candle = new LtpCacheService.MinuteCandle(
                candleMinute,
                apiCandle.open(), apiCandle.high(), apiCandle.low(), apiCandle.close());
        if (isAtrPreviousDaySource(trigger) && trigger.getCreatedAt() != null
                && candleMinute.isBefore(trigger.getCreatedAt().truncatedTo(ChronoUnit.MINUTES))) {
            log.debug("Waiting for a post-request one-minute candle for ATR previous-day request {}. requestCreated={} candleMinute={}",
                    trigger.getId(), trigger.getCreatedAt(), candleMinute);
            return OpeningDecision.WAIT;
        }
        boolean isPe = "PE".equalsIgnoreCase(trigger.getOptionType());
        boolean openingRuleActive = ATR_SIGNAL_SOURCE.equalsIgnoreCase(trigger.getSource())
                && nowIst.toLocalTime().isBefore(OPENING_RULE_CUTOFF);
        if (openingRuleActive) {
            Double target1 = trigger.getTarget1();
            if (target1 != null && target1 > 0d) {
                boolean targetTouchedNow = isPe ? currentSpotPrice <= target1 : currentSpotPrice >= target1;
                boolean targetTouchedSinceMarketOpen = ltpCacheService.hasPriceTouchedSince(
                        spotScripCode, nowIst.toLocalDate().atTime(9, 15), target1, isPe);
                boolean targetTouchedInCompletedCandle = isPe ? candle.low() <= target1 : candle.high() >= target1;
                boolean targetTouched = targetTouchedNow || targetTouchedSinceMarketOpen || targetTouchedInCompletedCandle;
                if (targetTouched) {
                    int claimed = triggerRepo.claimIfStatusEquals(trigger.getId(),
                            TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                            TriggeredTradeStatus.REJECTED.name());
                    if (claimed == 1) {
                        log.info("Opening Rule B rejected request {} because T1 {} was touched before entry in candle {}",
                                trigger.getId(), target1, candle.minute());
                    }
                    return OpeningDecision.REJECTED;
                }
            }

            Double stopLoss = trigger.getStopLoss();
            if (stopLoss != null && stopLoss > 0d) {
                boolean stopClosed = isPe ? candle.close() >= stopLoss : candle.close() <= stopLoss;
                if (stopClosed) {
                    if (!Boolean.TRUE.equals(trigger.getOpeningRuleReset())) {
                        trigger.setOpeningRuleReset(Boolean.TRUE);
                        triggerRepo.save(trigger);
                        log.info("Opening Rule B reset request {} after candle {} closed beyond SL {}. Request remains pending for candle-confirmed retrigger.",
                                trigger.getId(), candle.minute(), stopLoss);
                    }
                    return OpeningDecision.WAIT;
                }
            }
        }

        double entryPrice = trigger.getEntryPrice();
        boolean candleConfirmed = isPe ? candle.close() <= entryPrice : candle.close() >= entryPrice;
        boolean currentSpotStillValid = isPe ? currentSpotPrice <= entryPrice : currentSpotPrice >= entryPrice;
        if (!candleConfirmed || !currentSpotStillValid) {
            return OpeningDecision.WAIT;
        }
        log.info("ATR one-minute entry confirmed for request {} direction={} candleMinute={} candleClose={} entry={} currentSpot={}",
                trigger.getId(), isPe ? "PE" : "CE", candle.minute(), candle.close(), entryPrice, currentSpotPrice);
        TradeEventLogger.logAtrSpotEntryConfirmed(trigger, candle.minute().toString(), candle.close(), entryPrice, currentSpotPrice);
        return OpeningDecision.READY;
    }

    private void initializeGapPolicy(TriggerTradeRequestEntity trigger) {
        if (trigger == null || !isAtrSource(trigger.getSource())
                || Boolean.TRUE.equals(trigger.getGapPolicyInitialized())
                || trigger.getSpotScripCode() == null || trigger.getEntryPrice() == null
                || trigger.getTarget1() == null || trigger.getStopLoss() == null) {
            return;
        }

        LocalDateTime attemptTime = nowIst();
        if (trigger.getId() != null) {
            LocalDateTime previousAttempt = gapPolicyAttemptedAt.get(trigger.getId());
            if (previousAttempt != null && Duration.between(previousAttempt, attemptTime).toSeconds() < 60) {
                return;
            }
            gapPolicyAttemptedAt.put(trigger.getId(), attemptTime);
        }

        OptionalDouble marketOpenOpt = sharekhanHistoricalService.getTodayMarketOpenPrice(trigger.getSpotScripCode());
        OptionalDouble previousCloseOpt = sharekhanHistoricalService.getPreviousTradingClose(trigger.getSpotScripCode());
        if (marketOpenOpt.isEmpty() || previousCloseOpt.isEmpty()) {
            return;
        }

        double dayOpen = marketOpenOpt.getAsDouble();
        double previousClose = previousCloseOpt.getAsDouble();
        double entry = trigger.getEntryPrice();
        double target1 = trigger.getTarget1();
        double originalStop = trigger.getStopLoss();
        boolean isPe = "PE".equalsIgnoreCase(trigger.getOptionType());
        boolean qualifyingGap = isPe
                ? dayOpen < previousClose && target1 < dayOpen && dayOpen < entry
                : dayOpen > previousClose && entry < dayOpen && dayOpen < target1;

        trigger.setGapPolicyInitialized(Boolean.TRUE);
        trigger.setGapDayOpen(dayOpen);
        trigger.setGapPreviousClose(previousClose);
        trigger.setGapReentryCount(trigger.getGapReentryCount() != null ? trigger.getGapReentryCount() : 0);
        trigger.setGapProtectionEnabled(qualifyingGap);
        if (qualifyingGap) {
            trigger.setGapStopLoss(isPe
                    ? Math.min(originalStop, previousClose)
                    : Math.max(originalStop, previousClose));
            log.info("Gap protection enabled for request {} symbol={} direction={} previousClose={} dayOpen={} entry={} T1={} gapStop={}",
                    trigger.getId(), trigger.getSymbol(), isPe ? "PE" : "CE", previousClose, dayOpen,
                    entry, target1, trigger.getGapStopLoss());
        }
        triggerRepo.save(trigger);
        if (trigger.getId() != null) {
            gapPolicyAttemptedAt.remove(trigger.getId());
        }
    }

    private enum OpeningDecision {
        NORMAL,
        WAIT,
        READY,
        REJECTED
    }

    LocalDateTime nowIst() {
        return LocalDateTime.now(IST_ZONE);
    }

    private boolean isEquityMarketOpen(LocalDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime localTime = time.toLocalTime();
        return !localTime.isBefore(ENTRY_EVALUATION_START) && !localTime.isAfter(LocalTime.of(15, 30));
    }

    private boolean isIntradayEntryWindowOpen(LocalDateTime time) {
        return isEquityMarketOpen(time) && time.toLocalTime().isBefore(INTRADAY_ENTRY_CUTOFF);
    }

    private Optional<ReferencePrice> getTodayOpenReferencePrice(TriggerTradeRequestEntity trigger,
                                                                 Integer referenceScrip) {
        // An entry price can only be compared with an opening price captured for the
        // same traded instrument. Spot/index historical responses must never be used
        // to validate an option premium.
        if (trigger == null || referenceScrip == null || !referenceScrip.equals(trigger.getScripCode())) {
            return Optional.empty();
        }

        // Do not fall back to the broker historical endpoint here. It can return an
        // incorrectly mapped index/contract candle; the live cache is keyed by the
        // subscribed traded scrip and is therefore safe to compare.
        Double openingPrice = ltpCacheService.getTodayOpeningPrice(referenceScrip);
        if (openingPrice == null || !Double.isFinite(openingPrice) || openingPrice <= 0d) {
            return Optional.empty();
        }
        return Optional.of(new ReferencePrice("captured open", openingPrice));
    }

    private boolean entryExecutionOwnsOrHasPersistedTrade(TriggerTradeRequestEntity trigger) {
        if (trigger == null || trigger.getId() == null) {
            return false;
        }
        if (orderExecutionDispatcher.isInFlight(orderExecutionKey("ENTRY:" + trigger.getId(),
                trigger.getBrokerCredentialsId()))) {
            return true;
        }
        List<TriggeredTradeSetupEntity> persisted = triggeredRepo.findByTriggerRequestId(trigger.getId());
        return persisted != null && !persisted.isEmpty();
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
                    MONITORABLE_TRADE_STATUSES
            );

            for (TriggeredTradeSetupEntity trade : trades) {
                try {
                    if (isSpotTickForOptionTrade(scripCode, trade)) {
                        log.warn("Skipping trade {} in traded-instrument monitor branch because scripCode {} is the spot scrip for option trade. Waiting for traded option scrip {}.",
                                trade.getId(), scripCode, trade.getScripCode());
                        continue;
                    }

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
                    MONITORABLE_TRADE_STATUSES
            );
             for (TriggeredTradeSetupEntity trade : spotTrades) {
                try {
                     Double tradedLtp = resolveTradedLtpForSpotTick(trade, scripCode);

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

    private Double resolveTradedLtpForSpotTick(TriggeredTradeSetupEntity trade, Integer spotTickScripCode) {
        if (trade == null || trade.getScripCode() == null) {
            return null;
        }

        if (isSpotTickForOptionTrade(spotTickScripCode, trade)) {
            Integer tradedScripCode = trade.getScripCode();
            if (tradedScripCode.equals(spotTickScripCode)) {
                log.warn("Cannot monitor option trade {} from spot tick {} because traded scrip and spot scrip are identical. Refusing to use spot LTP as order price.",
                        trade.getId(), spotTickScripCode);
                return null;
            }
        }

        Double tradedLtp = ltpCacheService.getLtp(trade.getScripCode());

        if (tradedLtp == null) {
            tradedLtp = fetchLtpFromMStock(trade.getScripCode(), trade.getId(), "traded");
        }

        return tradedLtp;
    }

    private boolean isSpotTickForOptionTrade(Integer scripCode, TriggeredTradeSetupEntity trade) {
        return scripCode != null
                && trade != null
                && hasOptionType(trade.getOptionType())
                && trade.getSpotScripCode() != null
                && scripCode.equals(trade.getSpotScripCode());
    }

    protected void handleTradeWithLock(Long tradeId, double tradedLtp, Double spotLtp) {
        try {
            OptionalDouble confirmedStopPrice = reconfirmOptionPremiumStopWithShoonya(tradeId, tradedLtp);
            if (confirmedStopPrice.isEmpty()) {
                return;
            }
            final double confirmedTradedLtp = confirmedStopPrice.getAsDouble();
            final TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            // Execute quick transaction: read the current trade and attempt atomic claim if condition met
            Integer claimed = txTemplate.execute(status -> {
                var opt = triggeredRepo.findById(tradeId);
                if (opt.isEmpty()) return 0;
                TriggeredTradeSetupEntity persisted = opt.get();

                // Only act if still in EXECUTED or TARGET_ORDER_PLACED
                TriggeredTradeStatus currentStatus = persisted.getStatus();
                if (currentStatus == TriggeredTradeStatus.EXIT_TRIGGERED) {
                    return hasNoExitOrderId(persisted) && isExitRecoveryDue(persisted)
                            ? CLAIM_RECOVER_UNPLACED_EXIT
                            : CLAIM_NONE;
                }
                if (currentStatus != TriggeredTradeStatus.EXECUTED && currentStatus != TriggeredTradeStatus.TARGET_ORDER_PLACED) {
                    return CLAIM_NONE;
                }

                // Determine effective prices for SL and Target
                boolean usesSpotSl = usesSpotForSl(persisted);
                boolean usesSpotTarget = usesSpotForTarget(persisted);

                // Keep both ternary branches boxed. If the first completed spot candle does not
                // exist yet, resolveSpotStopClose returns null; mixing that Double with the
                // primitive tradedLtp would otherwise force null unboxing and abort all target
                // evaluation during the trade's first partial minute.
                Double slRefPrice = usesSpotSl
                        ? resolveSpotStopClose(persisted)
                        : Double.valueOf(confirmedTradedLtp);
                Double targetRefPrice = usesSpotTarget ? spotLtp : confirmedTradedLtp;

                if (usesSpotSl && slRefPrice == null) {
                    log.debug("Spot SL requested for trade {} but spot LTP unavailable; skipping SL evaluation", tradeId);
                }
                if (usesSpotTarget && targetRefPrice == null) {
                    log.debug("Spot target requested for trade {} but spot LTP unavailable; skipping target evaluation", tradeId);
                }

                Double slVal = persisted.getStopLoss();
                boolean hasValidSl = (slVal != null && slVal > 0d);

                if (isAtrPreviousDayMaxLossHit(persisted, confirmedTradedLtp)) {
                    return triggeredRepo.claimIfStatusEquals(tradeId,
                            TriggeredTradeStatus.EXECUTED.name(),
                            TriggeredTradeStatus.EXIT_TRIGGERED.name(),
                            "PER_LOT_MAX_LOSS_HIT");
                }

                if (isGapFillStopHit(persisted)) {
                    if (!hasSafeTradedExitPrice(persisted, confirmedTradedLtp, spotLtp,
                            persisted.getGapStopLoss(), "Gap fill stop")) {
                        return CLAIM_NONE;
                    }
                    return triggeredRepo.claimIfStatusEquals(tradeId,
                            TriggeredTradeStatus.EXECUTED.name(),
                            TriggeredTradeStatus.EXIT_TRIGGERED.name(),
                            GAP_FILL_EXIT_REASON);
                }

                // A spot-based SL is candle-confirmed, but the live spot price must still be
                // beyond the level. This prevents an exit after a completed candle briefly
                // breached the SL but the market has already recovered by evaluation time.
                boolean slHit = false;
                if (slRefPrice != null && hasValidSl) {
                    boolean isSpotSl = usesSpotSl;
                    boolean isPE = "PE".equalsIgnoreCase(persisted.getOptionType());

                    if (isSpotSl) {
                        if (spotLtp == null) {
                            log.debug("Spot SL requested for trade {} but current spot LTP unavailable; skipping SL evaluation", tradeId);
                        } else if (isPE) {
                            // For PE, both the completed candle close and current spot must be at/above SL.
                            slHit = slRefPrice >= slVal && spotLtp >= slVal;
                        } else {
                            // For CE, both the completed candle close and current spot must be at/below SL.
                            slHit = slRefPrice <= slVal && spotLtp <= slVal;
                        }
                    } else {
                        // Option-price SL: hit immediately from the traded instrument LTP.
                        slHit = slRefPrice <= slVal;
                    }
                }

                if (slHit) {
                    if (!hasSafeTradedExitPrice(persisted, confirmedTradedLtp, spotLtp, slRefPrice, "SL")) {
                        return CLAIM_NONE;
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
                            if (!canExitSpotTargetAtCurrentOptionPrice(persisted, confirmedTradedLtp)) {
                                return CLAIM_NONE;
                            }
                            if (!hasSafeTradedExitPrice(persisted, confirmedTradedLtp, spotLtp, targetRefPrice, "Target")) {
                                return CLAIM_NONE;
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
                            if (!canExitSpotTargetAtCurrentOptionPrice(persisted, confirmedTradedLtp)) {
                                return CLAIM_NONE;
                            }
                            if (!hasSafeTradedExitPrice(persisted, confirmedTradedLtp, spotLtp, targetRefPrice, "Target")) {
                                return CLAIM_NONE;
                            }
                            int updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            if (updated == 0) {
                                updated = triggeredRepo.claimIfStatusEquals(tradeId, TriggeredTradeStatus.TARGET_ORDER_PLACED.name(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), "TARGET_HIT");
                            }
                            return updated;
                        }
                    }
                }

                return CLAIM_NONE;
            });

            if (claimed != null && claimed == CLAIM_RECOVER_UNPLACED_EXIT) {
                TriggeredTradeSetupEntity reloaded = triggeredRepo.findById(tradeId)
                        .orElseThrow(() -> new RuntimeException("Trade not found during exit recovery: " + tradeId));
                recoverUnplacedExitOrder(reloaded, confirmedTradedLtp, spotLtp);
                persistPnlIfMissing(reloaded, confirmedTradedLtp);
            } else if (claimed != null && claimed == CLAIM_EXIT_TRIGGERED) {
                // Claim succeeded — now re-load the entity (outside the short transaction) and proceed to squareOff
                TriggeredTradeSetupEntity reloaded = triggeredRepo.findById(tradeId).orElseThrow(() -> new RuntimeException("Trade not found after claim: " + tradeId));
                
                // Re-determine effective prices for logging/logic
                Double slRefPrice = usesSpotForSl(reloaded)
                        ? resolveSpotStopClose(reloaded)
                        : Double.valueOf(confirmedTradedLtp);
                Double targetRefPrice = usesSpotForTarget(reloaded) ? spotLtp : confirmedTradedLtp;

                String exitReason = reloaded.getExitReason();
                TradeEventLogger.logExitTriggered(reloaded, exitReason, slRefPrice, targetRefPrice, confirmedTradedLtp, spotLtp);
                boolean exitOrderAlreadyPresent = reloaded.getExitOrderId() != null && !reloaded.getExitOrderId().isBlank();
                if ("STOP_LOSS_HIT".equals(exitReason) || GAP_FILL_EXIT_REASON.equals(exitReason)) {
                    Double stopPriceOption = GAP_FILL_EXIT_REASON.equals(exitReason)
                            ? reloaded.getGapStopLoss()
                            : reloaded.getStopLoss();
                    boolean usesSpotSl = usesSpotForSl(reloaded);

                    boolean modified = false;
                    if (exitOrderAlreadyPresent) {
                        // Prefer the latest traded LTP so the broker order executes immediately; fall back to a configured SL.
                        Double modifyPrice = null;
                        if (Double.isFinite(confirmedTradedLtp) && confirmedTradedLtp > 0d) {
                            modifyPrice = confirmedTradedLtp;
                        } else if (stopPriceOption != null && stopPriceOption > 0d) {
                            modifyPrice = stopPriceOption;
                        }
                        if (modifyPrice != null) {
                            modified = tradeExecutionService.modifyExitOrderForStop(reloaded, modifyPrice);
                        }
                    }

                    Double triggerPriceForLog = stopPriceOption != null ? stopPriceOption : slRefPrice;
                    TradeEventLogger.logStopLossTriggered(reloaded, triggerPriceForLog, confirmedTradedLtp, spotLtp);

                    if (modified) {
                        log.warn("📉 SL hit for trade {} - modified existing exit order {} to price {}", tradeId, reloaded.getExitOrderId(), stopPriceOption);
                    } else {
                        log.warn("📉 SL hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff", tradeId, slRefPrice, confirmedTradedLtp);
                        dispatchSquareOff(reloaded, confirmedTradedLtp, exitReason);
                    }
                } else {
                    // TARGET_HIT
                    if (exitOrderAlreadyPresent) {
                        log.info("🎯 Target hit for trade {} - existing exit order {} already placed. Modifying toward traded LTP {}.",
                                tradeId, reloaded.getExitOrderId(), confirmedTradedLtp);
                        boolean modified = tradeExecutionService.modifyExitOrderForTarget(reloaded, confirmedTradedLtp);
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
                            log.info("🎯 Target hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff (Single/Unknown Lot)", tradeId, targetRefPrice, confirmedTradedLtp);
                            dispatchSquareOff(reloaded, confirmedTradedLtp, "TARGET_HIT");
                        } else {
                            handlePartialBooking(reloaded, targetRefPrice, confirmedTradedLtp, lots);
                        }
                    } else {
                        log.info("🎯 Target hit for trade {} at RefLTP: {} (TradedLTP: {}) - proceeding to squareOff (TSL Disabled)", tradeId, targetRefPrice, confirmedTradedLtp);
                        dispatchSquareOff(reloaded, confirmedTradedLtp, "TARGET_HIT");
                    }
                }

                // ensure pnl persisted if needed
                persistPnlIfMissing(reloaded, confirmedTradedLtp);
            } else {
                log.debug("No claim performed for trade {} (claimed={})", tradeId, claimed);
            }
        } catch (Exception e) {
            log.error("❌ Error in handleTradeWithLock for trade {}: {}", tradeId, e.getMessage(), e);
        }
    }

    /**
     * A websocket/cache tick is useful for speed, but it must not by itself close an
     * option-premium trade.  AUBANK 1060CE demonstrated why: a cached 32.80 tick
     * triggered an SL while the fresh Shoonya quote for the same contract was 40.95.
     *
     * This check applies only when the cached price is already below the configured
     * option-premium SL.  Normal target monitoring remains non-blocking, while an
     * actual SL exit is decided using the fresh Shoonya price.
     */
    private OptionalDouble reconfirmOptionPremiumStopWithShoonya(Long tradeId, double cachedLtp) {
        TriggeredTradeSetupEntity trade = triggeredRepo.findById(tradeId).orElse(null);
        if (trade == null
                || usesSpotForSl(trade)
                || !hasOptionType(trade.getOptionType())
                || trade.getStopLoss() == null
                || trade.getStopLoss() <= 0d
                || cachedLtp > trade.getStopLoss()) {
            return OptionalDouble.of(cachedLtp);
        }

        ScriptMasterEntity script = scriptMasterRepository.findByScripCode(trade.getScripCode());
        // A missing/non-F&O master record cannot be verified through the option API.
        // Preserve the existing path for that unsupported legacy case.
        if (!isFnoOption(script)) {
            return OptionalDouble.of(cachedLtp);
        }
        if (shoonyaQuoteService == null) {
            log.error("Skipping option SL exit for trade {} because Shoonya confirmation is unavailable. cachedLtp={} stopLoss={}",
                    tradeId, cachedLtp, trade.getStopLoss());
            return OptionalDouble.empty();
        }

        try {
            Optional<ShoonyaQuoteService.LiveQuote> quoteOpt = shoonyaQuoteService.getOptionQuote(script);
            if (quoteOpt.isEmpty()) {
                log.warn("Skipping option SL exit for trade {} because Shoonya returned no fresh quote. cachedLtp={} stopLoss={}",
                        tradeId, cachedLtp, trade.getStopLoss());
                return OptionalDouble.empty();
            }

            ShoonyaQuoteService.LiveQuote quote = quoteOpt.get();
            Double freshLtp = quote.referencePrice();
            if (freshLtp == null || !Double.isFinite(freshLtp) || freshLtp <= 0d) {
                log.warn("Skipping option SL exit for trade {} because Shoonya returned an invalid quote. cachedLtp={} stopLoss={}",
                        tradeId, cachedLtp, trade.getStopLoss());
                return OptionalDouble.empty();
            }

            ltpCacheService.updateLtp(trade.getScripCode(), freshLtp);
            if (freshLtp > trade.getStopLoss()) {
                log.warn("Rejected cached option SL tick for trade {}: cachedLtp={} stopLoss={} but fresh Shoonya quote is ltp={} bid={} ask={}",
                        tradeId, cachedLtp, trade.getStopLoss(), freshLtp, quote.bestBid(), quote.bestAsk());
                return OptionalDouble.empty();
            }

            log.info("Confirmed option SL for trade {} with fresh Shoonya quote: cachedLtp={} confirmedLtp={} bid={} ask={} stopLoss={}",
                    tradeId, cachedLtp, freshLtp, quote.bestBid(), quote.bestAsk(), trade.getStopLoss());
            return OptionalDouble.of(freshLtp);
        } catch (Exception e) {
            log.warn("Skipping option SL exit for trade {} because Shoonya confirmation failed: {}", tradeId, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    private boolean hasNoExitOrderId(TriggeredTradeSetupEntity trade) {
        return trade == null || trade.getExitOrderId() == null || trade.getExitOrderId().isBlank();
    }

    private boolean isExitRecoveryDue(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getExitClaimedAt() == null) {
            // Legacy rows created before exit_claimed_at was introduced can be
            // recovered immediately; newly claimed rows always carry a timestamp.
            return true;
        }
        return !trade.getExitClaimedAt().isAfter(nowIst().minus(EXIT_RECOVERY_GRACE_PERIOD));
    }

    private void dispatchSquareOff(TriggeredTradeSetupEntity trade, double tradedLtp, String exitReason) {
        if (trade == null || trade.getId() == null) {
            return;
        }
        String key = orderExecutionKey("EXIT:" + trade.getId(), trade.getBrokerCredentialsId());
        if (!orderExecutionDispatcher.submit(key,
                () -> tradeExecutionService.squareOff(trade, tradedLtp, exitReason))) {
            log.debug("Exit execution already queued/running for trade {}", trade.getId());
        }
    }

    private String orderExecutionKey(String liveKey, Long brokerCredentialsId) {
        if (brokerCredentialsId == null) {
            return liveKey;
        }
        try {
            return brokerCredentialsRepository.findById(brokerCredentialsId)
                    .filter(credentials -> credentials.getBrokerName() != null
                            && "Simulator".equalsIgnoreCase(credentials.getBrokerName()))
                    .map(credentials -> "SIM:" + liveKey)
                    .orElse(liveKey);
        } catch (Exception e) {
            // Do not risk routing a live order to the low-priority simulator queue.
            log.debug("Unable to resolve broker priority for {}: {}", liveKey, e.getMessage());
            return liveKey;
        }
    }

    private void recoverUnplacedExitOrder(TriggeredTradeSetupEntity trade, double tradedLtp, Double spotLtp) {
        if (trade == null || trade.getStatus() != TriggeredTradeStatus.EXIT_TRIGGERED || !hasNoExitOrderId(trade)) {
            return;
        }

        String exitReason = trade.getExitReason();
        if (exitReason == null || exitReason.isBlank()) {
            exitReason = "TARGET_HIT";
        }

        log.warn("Recovering trade {} stuck in EXIT_TRIGGERED without exitOrderId. reason={} tradedLtp={} spotLtp={}",
                trade.getId(), exitReason, tradedLtp, spotLtp);

        if ("STOP_LOSS_HIT".equals(exitReason)
                || GAP_FILL_EXIT_REASON.equals(exitReason)
                || "TARGET_HIT_PARTIAL".equals(exitReason)
                || "TARGET_HIT_FULL".equals(exitReason)) {
            dispatchSquareOff(trade, tradedLtp, exitReason);
            return;
        }

        if (Boolean.TRUE.equals(trade.getTslEnabled())) {
            int lots = resolveCurrentLots(trade);
            Double targetRefPrice = usesSpotForTarget(trade) ? spotLtp : tradedLtp;
            if (lots > 1 && targetRefPrice != null) {
                handlePartialBooking(trade, targetRefPrice, tradedLtp, lots);
                return;
            }
        }

        dispatchSquareOff(trade, tradedLtp, exitReason);
    }

    private boolean isGapFillStopHit(TriggeredTradeSetupEntity trade) {
        if (trade == null || !Boolean.TRUE.equals(trade.getGapProtectionEnabled())
                || trade.getGapStopLoss() == null || trade.getGapStopLoss() <= 0d
                || trade.getSpotScripCode() == null || !usesSpotForSl(trade)) {
            return false;
        }
        LtpCacheService.MinuteCandle candle = ltpCacheService.getLastCompletedMinuteCandle(trade.getSpotScripCode());
        if (candle == null || candle.minute() == null) {
            return false;
        }
        if (trade.getEntryAt() != null
                && candle.minute().isBefore(trade.getEntryAt().truncatedTo(ChronoUnit.MINUTES))) {
            return false;
        }
        return "PE".equalsIgnoreCase(trade.getOptionType())
                ? candle.close() >= trade.getGapStopLoss()
                : candle.close() <= trade.getGapStopLoss();
    }

    private Double resolveSpotStopClose(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getSpotScripCode() == null) {
            return null;
        }
        LtpCacheService.MinuteCandle candle = ltpCacheService.getLastCompletedMinuteCandle(trade.getSpotScripCode());
        if (candle == null || candle.minute() == null) {
            return null;
        }
        if (trade.getEntryAt() != null
                && candle.minute().isBefore(trade.getEntryAt().truncatedTo(ChronoUnit.MINUTES))) {
            return null;
        }
        return candle.close();
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
        Double optionEntryPrice = resolveOptionCost(trade);
        Double newStopLoss = trade.getStopLoss();
        boolean newStopLossUsesTradedInstrument = false;
        boolean target1Hit = isTargetHit(trade, referenceLtp, 1);
        Double t1OptionStopLoss = resolveT1OptionStopLoss(trade, target1Hit, tradedLtp);

        if (step.targetNumber() == 1) {
            if (optionEntryPrice != null) {
                newStopLoss = optionEntryPrice;
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
            dispatchSquareOff(trade, tradedLtp, "TARGET_HIT_FULL");
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
            remainingTrade.setActualEntryPrice(optionEntryPrice);
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
            dispatchSquareOff(trade, tradedLtp, "TARGET_HIT_PARTIAL");
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

    private boolean hasSafeTradedExitPrice(TriggeredTradeSetupEntity trade,
                                           double tradedLtp,
                                           Double spotLtp,
                                           Double referencePrice,
                                           String actionLabel) {
        if (!tradeExecutionService.hasUsableTradedExitPrice(trade, tradedLtp)) {
            log.warn("{} hit for trade {} on reference price {}, but traded LTP {} looks invalid for the option. Waiting for correct option LTP.",
                    actionLabel,
                    trade != null ? trade.getId() : null,
                    referencePrice,
                    tradedLtp);
            return false;
        }

        if (looksLikeSpotLtpForOptionTrade(trade, tradedLtp, spotLtp)) {
            log.warn("{} hit for trade {} on reference price {}, but traded LTP {} matches spot LTP {}. Waiting for correct option LTP.",
                    actionLabel,
                    trade != null ? trade.getId() : null,
                    referencePrice,
                    tradedLtp,
                    spotLtp);
            return false;
        }

        return true;
    }

    private boolean canExitSpotTargetAtCurrentOptionPrice(TriggeredTradeSetupEntity trade, double tradedLtp) {
        if (!usesSpotForTarget(trade)) {
            return true;
        }
        Double actualEntryPrice = trade != null ? trade.getActualEntryPrice() : null;
        if (actualEntryPrice == null || actualEntryPrice <= 0d || !Double.isFinite(actualEntryPrice)) {
            return true;
        }
        Double minimumExitPrice = TradeCostCalculator.minimumProfitableExitPrice(trade, 0.05d);
        if (minimumExitPrice == null) {
            log.warn("Spot target reached for trade {}, but a minimum profitable option exit price could not be calculated. Keeping the position open.",
                    trade.getId());
            return false;
        }
        if (Double.isFinite(tradedLtp) && tradedLtp + 0.000001d >= minimumExitPrice) {
            return true;
        }
        log.info("🎯 Spot target reached for trade {}, but option LTP {} is below the net-profitable exit floor {} (entry {}). Keeping the position open.",
                trade.getId(), tradedLtp, minimumExitPrice, actualEntryPrice);
        return false;
    }

    private boolean looksLikeSpotLtpForOptionTrade(TriggeredTradeSetupEntity trade, double tradedLtp, Double spotLtp) {
        if (trade == null || !hasOptionType(trade.getOptionType())) {
            return false;
        }
        if (spotLtp == null || !Double.isFinite(spotLtp) || spotLtp <= 0d || !Double.isFinite(tradedLtp) || tradedLtp <= 0d) {
            return false;
        }

        double spotDistance = Math.abs(tradedLtp - spotLtp) / spotLtp;
        if (spotDistance > 0.02d) {
            return false;
        }

        Double optionReference = resolveOptionCost(trade);
        if (optionReference == null || optionReference <= 0d) {
            return tradedLtp > 1000d;
        }

        double optionDistance = Math.abs(tradedLtp - optionReference) / optionReference;
        return optionDistance > 0.50d;
    }

    private boolean hasOptionType(String optionType) {
        return optionType != null && !optionType.trim().isEmpty();
    }

    /** Hard loss cap for the prior-day ATR strategy, evaluated from actual option premium and actual lot size. */
    private boolean isAtrPreviousDayMaxLossHit(TriggeredTradeSetupEntity trade, double optionLtp) {
        if (trade == null || !ATR_PREVIOUS_DAY_SOURCE.equalsIgnoreCase(trade.getSource())
                || !Double.isFinite(optionLtp) || optionLtp <= 0d) {
            return false;
        }
        Double entry = trade.getActualEntryPrice();
        if (entry == null || !Double.isFinite(entry) || entry <= optionLtp) {
            return false;
        }
        int lots = resolveCurrentLots(trade);
        Long quantity = trade.getQuantity();
        if (lots <= 0 || quantity == null || quantity <= 0L) {
            return false;
        }
        double lossPerLot = (entry - optionLtp) * ((double) quantity / lots);
        if (lossPerLot < ATR_PREVIOUS_DAY_MAX_LOSS_PER_LOT) {
            return false;
        }
        log.warn("ATR previous-day max loss reached for trade {}: lossPerLot={} cap={} entry={} optionLtp={} lots={} quantity={}",
                trade.getId(), lossPerLot, ATR_PREVIOUS_DAY_MAX_LOSS_PER_LOT, entry, optionLtp, lots, quantity);
        return true;
    }

    private boolean isAtrSource(String source) {
        return ATR_SIGNAL_SOURCE.equalsIgnoreCase(source) || ATR_PREVIOUS_DAY_SOURCE.equalsIgnoreCase(source);
    }

    private boolean isAtrPreviousDaySource(TriggerTradeRequestEntity trigger) {
        return trigger != null && ATR_PREVIOUS_DAY_SOURCE.equalsIgnoreCase(trigger.getSource());
    }

    private boolean hasValidAtrPreviousDayRiskGeometry(TriggerTradeRequestEntity request) {
        if (request == null || request.getEntryPrice() == null || request.getStopLoss() == null
                || request.getEntryPrice() <= 0d || request.getStopLoss() <= 0d) {
            return false;
        }
        if ("CE".equalsIgnoreCase(request.getOptionType())) {
            return request.getStopLoss() < request.getEntryPrice();
        }
        if ("PE".equalsIgnoreCase(request.getOptionType())) {
            return request.getStopLoss() > request.getEntryPrice();
        }
        return false;
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
        if (usesSpotForEntry(trade)) {
            return null;
        }
        if (trade.getEntryPrice() != null && trade.getEntryPrice() > 0d) {
            return trade.getEntryPrice();
        }
        return null;
    }

    private boolean usesSpotForEntry(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForEntry())) {
            return true;
        }
        return trade.getUseSpotForEntry() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
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

            // P&L is realised only after the broker/order poller has confirmed a
            // terminal exit.  In particular, do not turn a freshly claimed
            // EXIT_TRIGGERED trade into EXITED_SUCCESS while its exit order is
            // merely queued; that used to race with partial booking.
            if (saved.getStatus() != TriggeredTradeStatus.EXITED_SUCCESS) {
                log.debug("Skipping PnL persistence for trade {} because its exit is not confirmed (status={})",
                        saved.getId(), saved.getStatus());
                return;
            }

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

            if (isFnoOption(script)) {
                Double shoonyaLtp = fetchLtpFromShoonya(script, tradeId, priceRole);
                if (shoonyaLtp != null) {
                    return shoonyaLtp;
                }
                log.warn("Shoonya quote unavailable for {} F&O scrip {}. Falling back to MStock LTP.",
                        priceRole, scripCode);
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

    private Double fetchLtpFromShoonya(ScriptMasterEntity script, Long tradeId, String priceRole) {
        if (shoonyaQuoteService == null) {
            return null;
        }
        try {
            Optional<ShoonyaQuoteService.LiveQuote> quoteOpt = shoonyaQuoteService.getOptionQuote(script);
            if (quoteOpt.isEmpty()) {
                return null;
            }
            ShoonyaQuoteService.LiveQuote quote = quoteOpt.get();
            Double ltp = quote.referencePrice();
            if (ltp == null || !Double.isFinite(ltp) || ltp <= 0d) {
                return null;
            }
            ltpCacheService.updateLtp(script.getScripCode(), ltp);
            log.info("Fetched missing {} LTP from Shoonya for trade {} scrip {} symbol {}: ltp={} bid={} ask={}",
                    priceRole, tradeId, script.getScripCode(), quote.tradingSymbol(), ltp, quote.bestBid(), quote.bestAsk());
            return ltp;
        } catch (Exception ex) {
            log.warn("Failed to fetch {} fallback LTP from Shoonya for trade {} scrip {}: {}",
                    priceRole, tradeId, script.getScripCode(), ex.getMessage());
            return null;
        }
    }

    private boolean isFnoOption(ScriptMasterEntity script) {
        if (script == null || script.getOptionType() == null || script.getOptionType().isBlank()) {
            return false;
        }
        String exchange = script.getExchange() == null ? "" : script.getExchange().trim();
        return "NF".equalsIgnoreCase(exchange) || "NFO".equalsIgnoreCase(exchange)
                || "BF".equalsIgnoreCase(exchange) || "BFO".equalsIgnoreCase(exchange);
    }
}
