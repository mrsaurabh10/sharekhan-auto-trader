package org.com.sharekhan.service;

import com.sharekhan.http.exceptions.SharekhanAPIException;
import com.sharekhan.model.OrderParams;
import com.sharekhan.SharekhanConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.exception.InvalidTradeRequestException;
import org.com.sharekhan.logging.TradeEventLogger;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.service.broker.BrokerService;
import org.com.sharekhan.service.broker.BrokerServiceFactory;
import org.com.sharekhan.service.broker.ModifiableEntryBrokerService;
import org.com.sharekhan.service.broker.OrderStatusBrokerService;
import org.com.sharekhan.service.broker.TriggerPriceEntryBrokerService;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.com.sharekhan.util.SharekhanConsoleSilencer;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionService {

    private static final Duration ORDER_LOCK_TIMEOUT = Duration.ofSeconds(8);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Map<String, String> INDEX_FUTURE_EXCHANGES = Map.of(
            "NIFTY", "NF",
            "BANKNIFTY", "NF",
            "FINNIFTY", "NF",
            "MIDCPNIFTY", "NF",
            "SENSEX", "BF"
    );
    private static final List<DateTimeFormatter> FUTURE_EXPIRY_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT)
    );
    private static final int MAX_ENTRY_ATTEMPTS = 3;
    private static final long FINAL_STATUS_CHECK_DELAY_MS = 2000L;
    private static final double SECOND_ATTEMPT_SPREAD_FRACTION = 0.25;

    public static class ModifyExitOrderResult {
        private final boolean success;
        private final String message;

        public ModifyExitOrderResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final TriggerTradeRequestRepository triggerTradeRequestRepo;
    private final TokenStoreService tokenStoreService; // ✅ holds current token
    private final LtpCacheService ltpCacheService;
    private final QuoteCacheService quoteCacheService;

    private final ApplicationEventPublisher eventPublisher;

    private final WebSocketSubscriptionService webSocketSubscriptionService;

    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockInstrumentResolver mStockInstrumentResolver;
    private final MStockLtpService mStockLtpService;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final BrokerServiceFactory brokerServiceFactory;
    private final OrderPlacementGuard orderPlacementGuard;

    private final ScheduledExecutorService exitChaseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "exit-chase-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<Long, ScheduledFuture<?>> exitChaseFutures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService entryChaseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "entry-chase-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<Long, ScheduledFuture<?>> entryChaseFutures = new ConcurrentHashMap<>();

    private static final class ExitChaseState {
        private boolean bufferedAttempted;
        private Double lastPrice;
    }

    private final ConcurrentMap<Long, ExitChaseState> exitChaseStates = new ConcurrentHashMap<>();

    private static final class EntryChaseState {
        private int modifyAttempts;
        private Double lastPrice;
    }

    private final ConcurrentMap<Long, EntryChaseState> entryChaseStates = new ConcurrentHashMap<>();

    @Autowired
    private UserConfigService userConfigService;

    private final CryptoService cryptoService;

    @Autowired
    private TelegramNotificationService telegramNotificationService;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.trading.entry.max-spread-percent:1.5}")
    private double entryMaxSpreadPercent;

    @Value("${app.trading.entry.quote-stale-ms:2000}")
    private long entryQuoteStaleMillis;

    private static final Duration DEFAULT_QUOTE_STALENESS = Duration.ofMillis(2000);

    private record EntryDiagnostics(boolean shouldPlace,
                                    String reason,
                                    Double spreadPercent,
                                    Double recommendedLimit,
                                    Double bestBid,
                                    Double bestAsk,
                                    Instant quoteTimestamp) { }

    private record ExitDiagnostics(String exitReason,
                                   Double recommendedLimit,
                                   Double triggerPrice) { }

    private EntryDiagnostics analyseEntry(TriggeredTradeSetupEntity trigger, Double ltp) {
        if (trigger == null || trigger.getScripCode() == null) {
            return new EntryDiagnostics(true, "NO_SCRIP_CODE", null, null, null, null, null);
        }
        if (quoteCacheService == null) {
            return new EntryDiagnostics(true, "NO_QUOTE_CACHE", null, ltp, null, null, null);
        }

        Optional<QuoteCacheService.QuoteSnapshot> snapshotOpt = quoteCacheService.getSnapshot(trigger.getScripCode());
        if (snapshotOpt.isEmpty()) {
            return new EntryDiagnostics(true, "NO_QUOTE", null, null, null, null, null);
        }

        QuoteCacheService.QuoteSnapshot snapshot = snapshotOpt.get();
        long staleMs = entryQuoteStaleMillis > 0 ? entryQuoteStaleMillis : DEFAULT_QUOTE_STALENESS.toMillis();
        boolean stale = quoteCacheService.isStale(snapshot, Duration.ofMillis(staleMs));
        if (stale) {
            return new EntryDiagnostics(true, "STALE_QUOTE", snapshot.getSpreadPercent(),
                    snapshot.getMidPrice(), snapshot.getBestBid(), snapshot.getBestAsk(), snapshot.getUpdatedAt());
        }

        Double spreadPercent = snapshot.getSpreadPercent();
        boolean shouldPlace = spreadPercent == null || spreadPercent <= entryMaxSpreadPercent;
        String reason = shouldPlace ? "SPREAD_OK" : "SPREAD_THRESHOLD_EXCEEDED";
        return new EntryDiagnostics(shouldPlace, reason, spreadPercent,
                snapshot.getMidPrice(), snapshot.getBestBid(), snapshot.getBestAsk(), snapshot.getUpdatedAt());
    }

    private void logEntryDiagnostics(TriggeredTradeSetupEntity trigger,
                                     EntryDiagnostics diagnostics,
                                     Double ltp) {
        if (trigger == null || diagnostics == null) {
            return;
        }
        String spread = formatPercent(diagnostics.spreadPercent());
        String bid = formatPrice(diagnostics.bestBid());
        String ask = formatPrice(diagnostics.bestAsk());
        String mid = formatPrice(diagnostics.recommendedLimit());
        String quoteTime = diagnostics.quoteTimestamp() != null ? diagnostics.quoteTimestamp().toString() : "NA";
        String ltpStr = formatPrice(ltp);

        if (diagnostics.shouldPlace()) {
            log.info("📋 Entry diagnostics trade {} decision=PLACE reason={} spread={} bid={} ask={} mid={} ltp={} quoteTime={}",
                    triggerLogId(trigger), diagnostics.reason(), spread, bid, ask, mid, ltpStr, quoteTime);
        } else {
            log.info("📋 Entry diagnostics trade {} decision=SKIP reason={} spread={} bid={} ask={} mid={} ltp={} quoteTime={}",
                    triggerLogId(trigger), diagnostics.reason(), spread, bid, ask, mid, ltpStr, quoteTime);
        }
    }

    private ExitDiagnostics analyseExit(TriggeredTradeSetupEntity trade,
                                        Double referencePrice,
                                        String exitReason) {
        if (trade == null) {
            return new ExitDiagnostics(exitReason, null, null);
        }
        String reasonKey = exitReason != null ? exitReason.toUpperCase(Locale.ROOT) : "";
        Double recommended = referencePrice;
        Double trigger = null;

        if ("STOP_LOSS_HIT".equals(reasonKey)) {
            trigger = trade.getStopLoss();
            if (!isSpotStopLoss(trade) && trigger != null) {
                recommended = trigger;
            }
        } else if (reasonKey.contains("TARGET")) {
            trigger = pickTargetPrice(trade);
            if (!isSpotTarget(trade) && trigger != null) {
                recommended = trigger;
            }
        } else if (reasonKey.contains("INTRADAY") || reasonKey.contains("FORCE")) {
            recommended = referencePrice;
        }

        return new ExitDiagnostics(exitReason, recommended, trigger);
    }

    private void logExitDiagnostics(TriggeredTradeSetupEntity trade,
                                    ExitDiagnostics diagnostics,
                                    Double actualExitPrice) {
        if (trade == null || diagnostics == null) {
            return;
        }
        String limit = formatPrice(diagnostics.recommendedLimit());
        String trigger = formatPrice(diagnostics.triggerPrice());
        String actual = formatPrice(actualExitPrice);

        log.info("📋 Exit diagnostics trade {} reason='{}' recommendedLimit={} trigger={} actualReference={}",
                trade.getId(), diagnostics.exitReason(), limit, trigger, actual);
    }

    private String formatPrice(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "NA";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatPercent(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "NA";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private Long triggerLogId(TriggeredTradeSetupEntity trigger) {
        if (trigger == null) {
            return null;
        }
        return trigger.getTriggerRequestId() != null ? trigger.getTriggerRequestId() : trigger.getId();
    }

    private boolean isSharekhanSource(String source) {
        return "Sharekhan".equalsIgnoreCase(source);
    }



    // Backwards compatible public API - by default allow service to compute quantity if missing
    public TriggerTradeRequestEntity executeTrade(TriggerRequest request) {
        return executeTrade(request, false);
    }

    // New overload: if requireQuantity==true, throw an exception and send telegram when quantity is null
    public TriggerTradeRequestEntity executeTrade(TriggerRequest request, boolean requireQuantity) {
        return executeTrade(request, requireQuantity, true);
    }

    private TriggerTradeRequestEntity executeTrade(TriggerRequest request,
                                                   boolean requireQuantity,
                                                   boolean attemptBrokerSideEntryOrder) {

        // Determine exchange and whether it's a no-strike exchange (NC/BC)
        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase();
        final boolean isNoStrikeExchange = exch != null && (exch.equals("NC") || exch.equals("BC"));

        ScriptMasterEntity script = resolveScriptForRequest(request, isNoStrikeExchange);

        // Logic for Lot Size configuration
        Long appUserId = request.getUserId();
        
        // 1. If explicit quantity provided, try to use it as number of lots if < 100 (assuming lots) or if "lots" field explicitly set?
        // Wait, request.getQuantity() comes from parser. Parser puts lot count into quantity if "Lots X" is found.
        // So if request.getQuantity() is small (e.g. 1, 2, 5), it is likely lots. If it's 50, 100, etc, it might be quantity.
        // But for options, usually we trade in lots. Let's assume request.getQuantity() IS the number of lots if it comes from the parser as "Lots 2".
        // However, existing logic treats request.getQuantity() as number of lots IF we multiply by lotSize later.
        // Let's see: `finalQuantity = (long) request.getQuantity() * lotSize;`
        // This implies request.getQuantity() IS ALREADY treated as number of LOTS for options (non-NC/BC).
        // Correct.
        
        // So if "Lots 2" was parsed into request.quantity = 2, then `finalQuantity = 2 * lotSize`. This is correct.
        
        // But we have logic that OVERRIDES request.getQuantity() if it's null.
        if (request.getQuantity() == null) {
            // Check for option_stock_lot_size override
            try {
                String lotSizeStr = userConfigService.getConfig(appUserId, "option_stock_lot_size", null);
                if (lotSizeStr != null && !lotSizeStr.trim().isEmpty()) {
                    int lotSize = Integer.parseInt(lotSizeStr.trim());
                    if (lotSize > 0) {
                        request.setQuantity(lotSize);
                    }
                }
            } catch (Exception ignore) { }

            if (request.getQuantity() == null) {
                int maxAmtPerTrade;
                int maxLossPerTrade;
                try {
                    String amtStr = userConfigService.getConfig(appUserId, "max_amount_per_trade", "25000");
                    String lossStr = userConfigService.getConfig(appUserId, "max_loss_per_trade", "8000");
                    maxAmtPerTrade = Integer.parseInt(amtStr);
                    maxLossPerTrade = Integer.parseInt(lossStr);
                } catch (Exception e) {
                    // In case of bad/missing config values, use safe defaults
                    maxAmtPerTrade = 25000;
                    maxLossPerTrade = 8000;
                }

                Double stopLoss = request.getStopLoss();
                if (stopLoss == null) {
                    // keep existing behavior: fallback stopLoss set to entryPrice (and keep trailing default marker)
                    stopLoss = request.getEntryPrice();
                    request.setStopLoss(0.05);
                }

                double lossPerShare = Math.abs(request.getEntryPrice() - stopLoss);
                if (lossPerShare == 0) {
                    request.setQuantity(0);
                } else {
                    // If this is a no-strike exchange (NC/BC) we compute quantity directly per-share (no lot-size)
                    if (isNoStrikeExchange) {
                        int quantityAsPerLoss = (int) (maxLossPerTrade / lossPerShare);
                        int quantityAsPerMaxAmt = (int) (maxAmtPerTrade / request.getEntryPrice());
                        int quantity = Math.min(quantityAsPerLoss, quantityAsPerMaxAmt);
                        if (quantity < 0) quantity = 0;
                        request.setQuantity(quantity);
                    } else {
                        int lotSizeForCalc = script.getLotSize() != null ? script.getLotSize() : 1;
                        int lossPerLot = (int) (lossPerShare * lotSizeForCalc);
                        if (lossPerLot == 0) {  // avoid division by zero
                            request.setQuantity(0);
                        } else {
                            int quantityAsPerLoss = maxLossPerTrade / lossPerLot;
                            int quantityAsPerMaxAmt = (int) (maxAmtPerTrade / (request.getEntryPrice() * lotSizeForCalc));
                            int quantity = Math.min(quantityAsPerLoss, quantityAsPerMaxAmt);
                            if (quantity < 0) quantity = 0;
                            request.setQuantity(quantity);
                        }
                    }
                }
            }
        }

        if (requireQuantity &&  (request.getQuantity() == null || request.getQuantity() <= 0)) {
            try {
                String title = "Invalid Trade Request: Missing Quantity";
                StringBuilder body = new StringBuilder();
                body.append("Instrument: ").append(request.getInstrument()).append("\n");
                body.append("Exchange: ").append(request.getExchange()).append("\n");
                body.append("EntryPrice: ").append(request.getEntryPrice()).append("\n");
                body.append("StopLoss: ").append(request.getStopLoss()).append("\n");
                body.append("Targets: ").append(request.getTarget1()).append(",").append(request.getTarget2()).append(",").append(request.getTarget3()).append("\n");
                body.append("Note: quantity was null and request rejected by server (requireQuantity=true)");
                telegramNotificationService.sendTradeMessageForUser(request.getUserId(), title, body.toString());
            } catch (Exception e) {
                log.warn("Failed to send telegram alert for missing quantity: {}", e.getMessage());
            }
            throw new InvalidTradeRequestException("Quantity is required and cannot be null");
        }
        int lotSize = script.getLotSize() != null ? script.getLotSize() : 1;
        long finalQuantity;
        if (isNoStrikeExchange) {
            // For NC/BC quantity is per-share (no lot multiplication)
            finalQuantity = request.getQuantity() != null ? request.getQuantity().longValue() : 0L;
        } else {
            finalQuantity = (long) request.getQuantity() * lotSize;
        }

        // Hard stop: never place or persist a trade with zero/negative quantity
        if (finalQuantity <= 0L) {
            try {
                String title = "Invalid Trade Request: Quantity <= 0 (blocked)";
                StringBuilder body = new StringBuilder();
                body.append("Instrument: ").append(request.getInstrument()).append("\n");
                body.append("Exchange: ").append(request.getExchange()).append("\n");
                body.append("EntryPrice: ").append(request.getEntryPrice()).append("\n");
                body.append("StopLoss: ").append(request.getStopLoss()).append("\n");
                body.append("Computed Qty (lots->shares): ").append(finalQuantity).append("\n");
                body.append("Reason: Quantity must be greater than zero. Request rejected and not persisted.");
                telegramNotificationService.sendTradeMessageForUser(request.getUserId(), title, body.toString());
            } catch (Exception e) {
                log.warn("Failed to send telegram alert for zero quantity block: {}", e.getMessage());
            }
            throw new InvalidTradeRequestException("Quantity must be greater than zero");
        }
        
        // Resolve spot scrip code if needed
        Integer spotScripCode = request.getSpotScripCode();
        boolean useSpotForEntry = Boolean.TRUE.equals(request.getUseSpotForEntry());
        boolean useSpotForSl = Boolean.TRUE.equals(request.getUseSpotForSl());
        boolean useSpotForTarget = Boolean.TRUE.equals(request.getUseSpotForTarget());
        
        // If the legacy useSpotPrice flag is set, enable all granular flags
        if (Boolean.TRUE.equals(request.getUseSpotPrice())) {
            useSpotForEntry = true;
            useSpotForSl = true;
            useSpotForTarget = true;
        }

        // If it's an option (has an option type), always try to resolve spot scrip code.
        if (hasOptionType(request.getOptionType()) && spotScripCode == null) {
            spotScripCode = resolveSpotScripCodeForOption(request.getInstrument());
        }

        TriggerTradeRequestEntity entity = TriggerTradeRequestEntity.builder()
                .symbol(request.getInstrument())
                .scripCode(script.getScripCode())
                .exchange(script.getExchange())
                .instrumentType(script.getInstrumentType())
                .strikePrice(request.getStrikePrice())
                .optionType(request.getOptionType())
                .expiry(request.getExpiry())
                .entryPrice(request.getEntryPrice())
                .stopLoss(request.getStopLoss())
                .target1(request.getTarget1())
                .target2(request.getTarget2())
                .target3(request.getTarget3())
                .trailingSl(request.getTrailingSl())
                .quantity(finalQuantity)
                .lots(request.getQuantity()) // Store the lots
                .tslEnabled(request.getTslEnabled()) // Store TSL flag
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .createdAt(LocalDateTime.now())
                .intraday(request.getIntraday())
                .brokerCredentialsId(request.getBrokerCredentialsId())
                .appUserId(request.getUserId())
                .useSpotForEntry(useSpotForEntry)
                .useSpotForSl(useSpotForSl)
                .useSpotForTarget(useSpotForTarget)
                .useSpotPrice(request.getUseSpotPrice()) // Store legacy flag
                .spotScripCode(spotScripCode)
                .source(request.getSource()) // store source
                .build();

        // Persist request entity (no more storing legacy customerId on the request)
        TriggerTradeRequestEntity saved = triggerTradeRequestRepository.save(entity);
        String key = entity.getExchange() + entity.getScripCode();
        webSocketSubscriptionService.subscribeToScrip(key);
        
        // Always subscribe to spot scrip if it exists for an option trade
        if (entity.getSpotScripCode() != null) {
            ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(entity.getSpotScripCode());
            if (spotScript != null) {
                subscribeToSpotFeed(spotScript);
            }
        }

        if (attemptBrokerSideEntryOrder) {
            tryPlaceBrokerSideEntryOrder(saved);
        }
        
        return saved;
    }

    private void tryPlaceBrokerSideEntryOrder(TriggerTradeRequestEntity requestEntity) {
        if (!isBrokerSideEntryTriggerEligible(requestEntity)) {
            return;
        }

        BrokerContext ctx = resolveBrokerContext(requestEntity.getBrokerCredentialsId(), requestEntity.getAppUserId());
        if (!isBrokerContextUsable(ctx)) {
            log.debug("Skipping broker-side entry trigger for request {} because broker context is unavailable",
                    requestEntity.getId());
            return;
        }

        try {
            Broker.fromDisplayName(ctx.getBrokerName());
        } catch (Exception e) {
            log.debug("Skipping broker-side entry trigger for request {} because broker {} is unsupported",
                    requestEntity.getId(), ctx.getBrokerName());
            return;
        }

        BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
        if (!(brokerService instanceof TriggerPriceEntryBrokerService triggerPriceEntryBroker)) {
            log.debug("Skipping broker-side entry trigger for request {} because broker service {} does not support trigger-price entries",
                    requestEntity.getId(), brokerService != null ? brokerService.getClass().getSimpleName() : "null");
            return;
        }

        String requestLockKey = buildEntryLockKey(requestEntity);
        try {
            orderPlacementGuard.withLock(requestLockKey, ORDER_LOCK_TIMEOUT, () -> {
                placeBrokerSideEntryOrderWithClaim(requestEntity, ctx, triggerPriceEntryBroker);
                return null;
            });
        } catch (OrderPlacementGuard.LockAcquisitionException e) {
            log.warn("⚠️ Broker-side entry trigger skipped for request {} because another entry placement is in progress: {}",
                    requestEntity.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("⚠️ Broker-side entry trigger attempt failed for request {}. Keeping earlier trigger flow active: {}",
                    requestEntity.getId(), e.getMessage());
            log.debug("Broker-side entry trigger failure", e);
        }
    }

    private void placeBrokerSideEntryOrderWithClaim(TriggerTradeRequestEntity requestEntity,
                                                    BrokerContext ctx,
                                                    TriggerPriceEntryBrokerService brokerService) {
        TriggerTradeRequestEntity latest = triggerTradeRequestRepository.findById(requestEntity.getId()).orElse(requestEntity);
        if (!isBrokerSideEntryTriggerEligible(latest)) {
            return;
        }

        int claimed = triggerTradeRequestRepository.claimIfStatusEquals(
                latest.getId(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.TRIGGERED.name());
        if (claimed != 1) {
            log.debug("Broker-side entry trigger request {} was not claimable; current flow will handle it", latest.getId());
            return;
        }

        latest.setStatus(TriggeredTradeStatus.TRIGGERED);
        requestEntity.setStatus(TriggeredTradeStatus.TRIGGERED);
        boolean brokerAccepted = false;

        try {
            double entryPrice = roundPrice(latest.getEntryPrice());
            TriggeredTradeSetupEntity pendingTrade = buildPendingEntryTradeFromRequest(latest, LocalDateTime.now());
            TradeEventLogger.logOrderAttempt("ENTRY_TRIGGER", pendingTrade, 1, "PLACE_TRIGGER", entryPrice, null);

            OrderPlacementResult result = brokerService.placeTriggerPriceEntryOrder(pendingTrade, ctx, entryPrice);
            if (result != null && result.getAttemptedPrice() == null) {
                result.setAttemptedPrice(entryPrice);
            }

            if (result == null || !result.isSuccess() || !isUsableBrokerOrderId(result.getOrderId())) {
                String reason = result != null && result.getRejectionReason() != null
                        ? result.getRejectionReason()
                        : "Broker-side entry trigger was not accepted";
                TradeEventLogger.logOrderRejected(
                        "ENTRY_TRIGGER",
                        pendingTrade,
                        reason,
                        entryPrice,
                        result != null ? result.getAttemptedPrice() : entryPrice);
                revertBrokerSideEntryClaim(latest, requestEntity);
                log.warn("⚠️ Broker-side entry trigger rejected for request {}. Continuing earlier websocket trigger flow. Reason={}",
                        latest.getId(), reason);
                return;
            }

            brokerAccepted = true;
            pendingTrade.setOrderId(result.getOrderId());

            if (isOrderPlacementFilled(result)) {
                applyImmediateEntryExecution(pendingTrade, result);
            }

            TriggeredTradeSetupEntity savedTrade = triggeredTradeRepo.save(pendingTrade);
            TradeEventLogger.logOrderAccepted("ENTRY_TRIGGER", savedTrade, result, entryPrice);

            if (TriggeredTradeStatus.EXECUTED.equals(savedTrade.getStatus())) {
                TradeEventLogger.logOrderExecuted("ENTRY", savedTrade, result.getExecutedPrice(), result.getStatus());
                handleEntryOrderExecution(savedTrade);
            } else {
                publishOrderPlaced(savedTrade);
            }

            log.info("✅ Broker-side entry trigger placed for request {} orderId={} entryPrice={}",
                    latest.getId(), result.getOrderId(), formatPrice(entryPrice));
        } catch (Exception e) {
            if (!brokerAccepted) {
                revertBrokerSideEntryClaim(latest, requestEntity);
                log.warn("⚠️ Broker-side entry trigger failed before broker acceptance for request {}. Earlier trigger flow remains active: {}",
                        latest.getId(), e.getMessage());
                return;
            }
            log.error("❌ Broker-side entry trigger was accepted for request {}, but local persistence/monitoring failed: {}",
                    latest.getId(), e.getMessage(), e);
        }
    }

    private boolean isBrokerSideEntryTriggerEligible(TriggerTradeRequestEntity requestEntity) {
        return requestEntity != null
                && requestEntity.getId() != null
                && TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(requestEntity.getStatus())
                && requestEntity.getEntryPrice() != null
                && requestEntity.getEntryPrice() > 0d
                && hasOptionType(requestEntity.getOptionType())
                && !usesSpotForEntry(requestEntity);
    }

    private boolean usesSpotForEntry(TriggerTradeRequestEntity requestEntity) {
        if (requestEntity == null) {
            return false;
        }
        return Boolean.TRUE.equals(requestEntity.getUseSpotForEntry())
                || (requestEntity.getUseSpotForEntry() == null && Boolean.TRUE.equals(requestEntity.getUseSpotPrice()));
    }

    private void revertBrokerSideEntryClaim(TriggerTradeRequestEntity latest,
                                            TriggerTradeRequestEntity originalReference) {
        if (latest == null || latest.getId() == null) {
            return;
        }
        int reverted = triggerTradeRequestRepository.claimIfStatusEquals(
                latest.getId(),
                TriggeredTradeStatus.TRIGGERED.name(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name());
        if (reverted == 1) {
            latest.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
            if (originalReference != null) {
                originalReference.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
            }
        } else {
            log.warn("Unable to revert broker-side entry trigger claim for request {}. It may have been handled by another flow.",
                    latest.getId());
        }
    }

    private TriggeredTradeSetupEntity buildPendingEntryTradeFromRequest(TriggerTradeRequestEntity requestEntity,
                                                                        LocalDateTime triggeredAt) {
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setTriggerRequestId(requestEntity.getId());
        trade.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        trade.setTriggeredAt(triggeredAt != null ? triggeredAt : LocalDateTime.now());
        trade.setScripCode(requestEntity.getScripCode());
        trade.setBrokerCredentialsId(requestEntity.getBrokerCredentialsId());
        trade.setAppUserId(requestEntity.getAppUserId());
        trade.setExchange(requestEntity.getExchange());
        trade.setSymbol(requestEntity.getSymbol());
        trade.setQuantity(requestEntity.getQuantity());
        trade.setLots(requestEntity.getLots());
        trade.setTslEnabled(requestEntity.getTslEnabled());
        trade.setInstrumentType(requestEntity.getInstrumentType());
        trade.setStrikePrice(requestEntity.getStrikePrice());
        trade.setOptionType(requestEntity.getOptionType());
        trade.setExpiry(requestEntity.getExpiry());
        trade.setIntraday(requestEntity.getIntraday());
        trade.setEntryPrice(requestEntity.getEntryPrice());
        trade.setStopLoss(requestEntity.getStopLoss());
        trade.setTarget1(requestEntity.getTarget1());
        trade.setTarget2(requestEntity.getTarget2());
        trade.setTarget3(requestEntity.getTarget3());
        trade.setTrailingSl(requestEntity.getTrailingSl());
        trade.setUseSpotForEntry(requestEntity.getUseSpotForEntry());
        trade.setUseSpotForSl(requestEntity.getUseSpotForSl());
        trade.setUseSpotForTarget(requestEntity.getUseSpotForTarget());
        trade.setUseSpotPrice(requestEntity.getUseSpotPrice());
        trade.setSpotScripCode(requestEntity.getSpotScripCode());
        trade.setSource(requestEntity.getSource());
        return trade;
    }

    private void applyImmediateEntryExecution(TriggeredTradeSetupEntity trade,
                                              OrderPlacementResult result) {
        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        trade.setEntryAt(LocalDateTime.now());

        Double executedPrice = result != null && result.getExecutedPrice() != null
                ? result.getExecutedPrice()
                : trade.getEntryPrice();
        if (executedPrice == null) {
            return;
        }

        Double originalEntryPrice = trade.getEntryPrice();
        trade.setActualEntryPrice(executedPrice);

        boolean isSharekhanSource = isSharekhanSource(trade.getSource());
        if (!isSharekhanSource) {
            if (originalEntryPrice != null) {
                double diff = executedPrice - originalEntryPrice;
                if (Math.abs(diff) > 0.0001) {
                    log.info("Adjusting SL and Targets for broker-side entry trade due to immediate execution price difference: {}. OriginalEntry={}, Executed={}",
                            diff, originalEntryPrice, executedPrice);
                    if (trade.getStopLoss() != null) {
                        trade.setStopLoss(trade.getStopLoss() + diff);
                    }
                    if (trade.getTarget1() != null) {
                        trade.setTarget1(trade.getTarget1() + diff);
                    }
                    if (trade.getTarget2() != null) {
                        trade.setTarget2(trade.getTarget2() + diff);
                    }
                    if (trade.getTarget3() != null) {
                        trade.setTarget3(trade.getTarget3() + diff);
                    }
                }
            }
            trade.setEntryPrice(executedPrice);
        }
    }

    private void publishOrderPlaced(TriggeredTradeSetupEntity trade) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new OrderPlacedEvent(trade));
                }
            });
        } else {
            eventPublisher.publishEvent(new OrderPlacedEvent(trade));
        }
    }

    private ScriptMasterEntity resolveScriptForRequest(TriggerRequest request, boolean isNoStrikeExchange) {
        if (request == null) {
            throw new InvalidTradeRequestException("Trade request cannot be null");
        }

        String instrument = request.getInstrument();
        if (instrument == null || instrument.isBlank()) {
            throw new InvalidTradeRequestException("Instrument is required");
        }

        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase(Locale.ROOT);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalTime cutoff = LocalTime.of(15, 30);

        if (!isNoStrikeExchange && request.getExpiry() == null && request.getStrikePrice() != null && Double.compare(request.getStrikePrice(), 0.0) != 0) {
            List<String> allExpiryStrings = scriptMasterRepository.findAllExpiriesByTradingSymbolAndStrikePriceAndOptionType(
                    request.getInstrument(),
                    request.getStrikePrice(),
                    request.getOptionType()
            );

            Optional<LocalDate> latestExpiryOpt = allExpiryStrings.stream()
                    .map(s -> {
                        try {
                            return LocalDate.parse(s, formatter);
                        } catch (DateTimeParseException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .filter(expiryDate -> {
                        LocalDateTime expiryCutoff = LocalDateTime.of(expiryDate, cutoff);
                        return expiryDate.isAfter(now.toLocalDate()) ||
                                (expiryDate.isEqual(now.toLocalDate()) && now.isBefore(expiryCutoff));
                    })
                    .min(Comparator.naturalOrder());

            if (latestExpiryOpt.isPresent()) {
                request.setExpiry(latestExpiryOpt.get().format(formatter));
            } else {
                throw new RuntimeException("No valid expiry found for the given instrument and strike price");
            }
        }

        if (isNoStrikeExchange) {
            if (exch == null) {
                throw new RuntimeException("Exchange is required for equity instruments");
            }
            Optional<ScriptMasterEntity> opt = scriptMasterRepository.findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull(
                    exch, instrument
            );
            if (opt.isPresent()) {
                return opt.get();
            }

            List<ScriptMasterEntity> allForExchange = scriptMasterRepository.findByExchangeIgnoreCase(exch);
            if (allForExchange == null) {
                allForExchange = List.of();
            }
            Optional<ScriptMasterEntity> match = allForExchange.stream()
                    .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(instrument))
                    .filter(s -> (s.getStrikePrice() == null || Double.compare(s.getStrikePrice(), 0.0) == 0)
                            && (s.getExpiry() == null || s.getExpiry().isBlank()))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }

            // Fallback: try any tradingSymbol match irrespective of strike/expiry
            return allForExchange.stream()
                    .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(instrument))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Script not found in master DB for instrument on exchange " + exch + " (strike & expiry null)"));
        }

        return scriptMasterRepository.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                instrument,
                request.getStrikePrice(),
                request.getOptionType(),
                request.getExpiry()
        ).orElseThrow(() -> new RuntimeException("Script not found in master DB"));
    }

    public TriggeredTradeSetupEntity executeQuickTrade(TriggerRequest request) {
        if (request == null) {
            throw new InvalidTradeRequestException("Quick trade request cannot be null");
        }

        if (request.getAction() != null && !"BUY".equalsIgnoreCase(request.getAction())) {
            throw new InvalidTradeRequestException("Quick trades currently support BUY instructions only");
        }

        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase(Locale.ROOT);
        final boolean isNoStrikeExchange = exch != null && (exch.equals("NC") || exch.equals("BC"));

        ScriptMasterEntity script = resolveScriptForRequest(request, isNoStrikeExchange);

        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            request.setQuantity(resolveQuickDefaultLots(request.getUserId()));
        }

        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new InvalidTradeRequestException("Quick trade quantity must be greater than zero");
        }

        Integer optionScripCode = script.getScripCode();
        Double ltp = null;
        boolean isMxExchange = "MX".equalsIgnoreCase(script.getExchange());
        if (!isMxExchange) {
            ltp = ltpCacheService.getLtp(optionScripCode);
            if (ltp == null) {
                log.debug("Quick trade LTP cache miss for scrip {} (instrument {}). Trying MStock fallback.", optionScripCode, request.getInstrument());
                ltp = fetchLtpViaMStockFallback(optionScripCode, "executeQuickTrade");
            }
            if (ltp == null) {
                log.warn("Quick trade LTP still unavailable for scrip {} after MStock fallback.", optionScripCode);
                throw new InvalidTradeRequestException("Live price unavailable for quick trade; please retry shortly");
            }
        }

        double entryPrice;
        if (ltp != null) {
            entryPrice = roundPrice(ltp);
        } else if (request.getEntryPrice() != null && request.getEntryPrice() > 0) {
            entryPrice = roundPrice(request.getEntryPrice());
        } else {
            throw new InvalidTradeRequestException("Entry price is required for MX exchange instruments");
        }
        double slPercent = resolvePercentageConfig(request.getUserId(), "quick_trade_sl_percent", 20.0);
        double targetPercent = resolvePercentageConfig(request.getUserId(), "quick_trade_target_percent", 40.0);
        double target2Percent = resolvePercentageConfig(request.getUserId(), "quick_trade_target2_percent", 0.0);

        Double stopLoss = slPercent > 0 ? roundPrice(entryPrice * (1 - slPercent / 100.0)) : null;
        Double target1 = targetPercent > 0 ? roundPrice(entryPrice * (1 + targetPercent / 100.0)) : null;
        Double target2 = target2Percent > 0 ? roundPrice(entryPrice * (1 + target2Percent / 100.0)) : null;

        request.setEntryPrice(entryPrice);
        request.setStopLoss(stopLoss);
        request.setTarget1(target1);
        request.setTarget2(target2);
        request.setTarget3(null);

        int lotSize = script.getLotSize() != null ? script.getLotSize() : 1;
        long finalQuantity = isNoStrikeExchange
                ? request.getQuantity().longValue()
                : (long) request.getQuantity() * lotSize;

        if (finalQuantity <= 0L) {
            throw new InvalidTradeRequestException("Computed quantity is not positive for quick trade");
        }

        Integer spotScripCode = request.getSpotScripCode();
        boolean useSpotForEntry = Boolean.TRUE.equals(request.getUseSpotForEntry());
        boolean useSpotForSl = Boolean.TRUE.equals(request.getUseSpotForSl());
        boolean useSpotForTarget = Boolean.TRUE.equals(request.getUseSpotForTarget());

        if (Boolean.TRUE.equals(request.getUseSpotPrice())) {
            useSpotForEntry = true;
            useSpotForSl = true;
            useSpotForTarget = true;
        }

        if (hasOptionType(request.getOptionType()) && spotScripCode == null) {
            spotScripCode = resolveSpotScripCodeForOption(request.getInstrument());
        }

        TriggerTradeRequestEntity entity = TriggerTradeRequestEntity.builder()
                .symbol(request.getInstrument())
                .scripCode(script.getScripCode())
                .exchange(script.getExchange())
                .instrumentType(script.getInstrumentType())
                .strikePrice(request.getStrikePrice())
                .optionType(request.getOptionType())
                .expiry(request.getExpiry())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .target3(request.getTarget3())
                .trailingSl(request.getTrailingSl())
                .quantity(finalQuantity)
                .lots(request.getQuantity())
                .tslEnabled(request.getTslEnabled())
                .status(TriggeredTradeStatus.TRIGGERED)
                .createdAt(LocalDateTime.now())
                .intraday(request.getIntraday())
                .brokerCredentialsId(request.getBrokerCredentialsId())
                .appUserId(request.getUserId())
                .useSpotForEntry(useSpotForEntry)
                .useSpotForSl(useSpotForSl)
                .useSpotForTarget(useSpotForTarget)
                .useSpotPrice(request.getUseSpotPrice())
                .spotScripCode(spotScripCode)
                .source(request.getSource())
                .build();

        TriggerTradeRequestEntity saved = triggerTradeRequestRepository.save(entity);
        String key = entity.getExchange() + entity.getScripCode();
        webSocketSubscriptionService.subscribeToScrip(key);

        if (spotScripCode != null) {
            ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(spotScripCode);
            if (spotScript != null) {
                subscribeToSpotFeed(spotScript);
            }
        }

        TriggeredTradeSetupEntity executed = executeTradeFromEntity(saved);
        if (executed == null) {
            triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                    TriggeredTradeStatus.TRIGGERED.name(),
                    TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name());
            saved.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
            triggerTradeRequestRepository.save(saved);
            throw new InvalidTradeRequestException("Quick trade could not be executed immediately; it has been queued instead.");
        }

        triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                TriggeredTradeStatus.TRIGGERED.name(),
                TriggeredTradeStatus.EXECUTED.name());
        saved.setStatus(TriggeredTradeStatus.EXECUTED);
        triggerTradeRequestRepository.save(saved);

        return executed;
    }

    public TriggerTradeRequestEntity executeTriggeredTrade(TriggerRequest request) {
        if (request == null) {
            throw new InvalidTradeRequestException("Trade request cannot be null");
        }

        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase(Locale.ROOT);
        final boolean isNoStrikeExchange = exch != null && (exch.equals("NC") || exch.equals("BC"));
        ScriptMasterEntity script = resolveScriptForRequest(request, isNoStrikeExchange);

        if (!isNoStrikeExchange) {
            Double optionLtp = ltpCacheService.getLtp(script.getScripCode());
            if (optionLtp == null) {
                log.debug("Immediate trigger LTP cache miss for scrip {} (instrument {}). Trying MStock fallback.",
                        script.getScripCode(), request.getInstrument());
                optionLtp = fetchLtpViaMStockFallback(script.getScripCode(), "executeTriggeredTrade");
            }
            if (optionLtp == null) {
                throw new InvalidTradeRequestException("Option LTP unavailable for immediate strategy execution; will retry on next evaluation");
            }
        }

        TriggerTradeRequestEntity saved = executeTrade(request, false, false);
        int claimed = triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.TRIGGERED.name());
        if (claimed == 1) {
            saved.setStatus(TriggeredTradeStatus.TRIGGERED);
        } else {
            saved = triggerTradeRequestRepository.findById(saved.getId()).orElse(saved);
            if (!TriggeredTradeStatus.TRIGGERED.equals(saved.getStatus())) {
                throw new InvalidTradeRequestException("Strategy request could not be claimed for immediate execution");
            }
        }

        TriggeredTradeSetupEntity executed = executeTradeFromEntity(saved);
        if (executed == null) {
            triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                    TriggeredTradeStatus.TRIGGERED.name(),
                    TriggeredTradeStatus.REJECTED.name());
            saved.setStatus(TriggeredTradeStatus.REJECTED);
            triggerTradeRequestRepository.save(saved);
            throw new InvalidTradeRequestException("Strategy entry could not be executed immediately after breakout");
        }

        if (TriggeredTradeStatus.REJECTED.equals(executed.getStatus())) {
            triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                    TriggeredTradeStatus.TRIGGERED.name(),
                    TriggeredTradeStatus.REJECTED.name());
            saved.setStatus(TriggeredTradeStatus.REJECTED);
            triggerTradeRequestRepository.save(saved);
            throw new InvalidTradeRequestException("Strategy entry order was rejected: " + executed.getExitReason());
        }

        if (TriggeredTradeStatus.EXECUTED.equals(executed.getStatus())) {
            triggerTradeRequestRepository.claimIfStatusEquals(saved.getId(),
                    TriggeredTradeStatus.TRIGGERED.name(),
                    TriggeredTradeStatus.EXECUTED.name());
            saved.setStatus(TriggeredTradeStatus.EXECUTED);
            triggerTradeRequestRepository.save(saved);
        }

        log.info("✅ Strategy-triggered trade request {} moved to {} with live trade {} status={}",
                saved.getId(), saved.getStatus(), executed.getId(), executed.getStatus());
        return saved;
    }

    public Optional<Integer> warmUpOptionLtp(TriggerRequest request, String reason) {
        if (request == null) {
            return Optional.empty();
        }
        try {
            ScriptMasterEntity script = resolveScriptForRequest(request, false);
            if (script == null || script.getScripCode() == null || !hasOptionType(script.getOptionType())) {
                return Optional.empty();
            }

            String feedKey = script.getExchange() + script.getScripCode();
            if (!webSocketSubscriptionService.getActiveScripKeys().contains(feedKey)) {
                boolean subscribed = webSocketSubscriptionService.subscribeToScrip(feedKey);
                if (subscribed) {
                    log.info("🔁 Warmed option LTP subscription for {} {} {} {} scrip={} reason={}",
                            request.getInstrument(),
                            request.getStrikePrice(),
                            request.getOptionType(),
                            request.getExpiry(),
                            feedKey,
                            reason);
                }
            } else {
                log.debug("Option LTP subscription already active for {} reason={}", feedKey, reason);
            }

            if (ltpCacheService.getLtp(script.getScripCode()) == null) {
                fetchLtpViaMStockFallback(script.getScripCode(), "strategyOptionWarmup");
            }
            return Optional.of(script.getScripCode());
        } catch (Exception e) {
            log.warn("Unable to warm option LTP subscription for {} {} {} {}: {}",
                    request.getInstrument(),
                    request.getStrikePrice(),
                    request.getOptionType(),
                    request.getExpiry(),
                    e.getMessage());
            log.debug("Option LTP warmup failed", e);
            return Optional.empty();
        }
    }

    public TriggeredTradeSetupEntity createExecutedTrade(TriggerRequest request) {
        // Reuse logic to resolve script and calculate quantity
        // We can call executeTrade to get the request entity, but we need to intercept it before saving or modify it after
        // Better to extract the common logic or just reuse executeTrade and then immediately convert it.
        // But executeTrade saves to triggerTradeRequestRepository. We want to save to triggeredTradeRepo directly.

        // Let's copy the logic for resolving script and quantity.
        // Initialize formatter for expiry date parsing
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalTime cutoff = LocalTime.of(15, 30); // 3:30 PM IST

        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase();
        final boolean isNoStrikeExchange = exch != null && (exch.equals("NC") || exch.equals("BC"));

        if (!isNoStrikeExchange && request.getExpiry() == null && request.getStrikePrice() != null && Double.compare(request.getStrikePrice(), 0.0) != 0) {
            List<String> allExpiryStrings = scriptMasterRepository.findAllExpiriesByTradingSymbolAndStrikePriceAndOptionType(
                    request.getInstrument(),
                    request.getStrikePrice(),
                    request.getOptionType()
            );
            Optional<LocalDate> latestExpiryOpt = allExpiryStrings.stream()
                    .map(s -> {
                        try { return LocalDate.parse(s, formatter); } catch (DateTimeParseException e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .filter(expiryDate -> {
                        LocalDateTime expiryCutoff = LocalDateTime.of(expiryDate, cutoff);
                        return expiryDate.isAfter(now.toLocalDate()) || (expiryDate.isEqual(now.toLocalDate()) && now.isBefore(expiryCutoff));
                    })
                    .min(Comparator.naturalOrder());

            if (latestExpiryOpt.isPresent()) {
                request.setExpiry(latestExpiryOpt.get().format(formatter));
            } else {
                throw new RuntimeException("No valid expiry found for the given instrument and strike price");
            }
        }

        ScriptMasterEntity script;
        if (isNoStrikeExchange) {
            Optional<ScriptMasterEntity> opt = scriptMasterRepository.findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull(exch, request.getInstrument());
            if (opt.isPresent()) {
                script = opt.get();
            } else {
                List<ScriptMasterEntity> allForExchange = scriptMasterRepository.findByExchangeIgnoreCase(exch);
                if (allForExchange == null) allForExchange = List.of();
                Optional<ScriptMasterEntity> match = allForExchange.stream()
                        .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(request.getInstrument()))
                        .filter(s -> (s.getStrikePrice() == null || Double.compare(s.getStrikePrice(), 0.0) == 0) && (s.getExpiry() == null || s.getExpiry().isBlank()))
                        .findFirst();
                if (match.isPresent()) {
                    script = match.get();
                } else {
                    Optional<ScriptMasterEntity> anyMatch = allForExchange.stream()
                            .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(request.getInstrument()))
                            .findFirst();
                    if (anyMatch.isPresent()) {
                        script = anyMatch.get();
                    } else {
                        throw new RuntimeException("Script not found in master DB for instrument on exchange " + exch);
                    }
                }
            }
        } else {
            script = scriptMasterRepository.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                    request.getInstrument(), request.getStrikePrice(), request.getOptionType(), request.getExpiry()
            ).orElseThrow(() -> new RuntimeException("Script not found in master DB"));
        }

        if (request.getQuantity() == null) {
            // ... (quantity calculation logic same as executeTrade) ...
            // For brevity, assuming quantity is provided or calculated same way.
            // If quantity is null here, we can default to 1 lot or throw error.
            // Let's assume user provides quantity for manual entry or we use default logic.
            // Copying logic:
            Long appUserId = request.getUserId();

            // Check for option_stock_lot_size override
            try {
                String lotSizeStr = userConfigService.getConfig(appUserId, "option_stock_lot_size", null);
                if (lotSizeStr != null && !lotSizeStr.trim().isEmpty()) {
                    int lotSize = Integer.parseInt(lotSizeStr.trim());
                    if (lotSize > 0) {
                        request.setQuantity(lotSize);
                    }
                }
            } catch (Exception ignore) { }

            if (request.getQuantity() == null) {
                int maxAmtPerTrade = 25000;
                int maxLossPerTrade = 8000;
                try {
                    String amtStr = userConfigService.getConfig(appUserId, "max_amount_per_trade", "25000");
                    String lossStr = userConfigService.getConfig(appUserId, "max_loss_per_trade", "8000");
                    maxAmtPerTrade = Integer.parseInt(amtStr);
                    maxLossPerTrade = Integer.parseInt(lossStr);
                } catch (Exception e) {}

                Double stopLoss = request.getStopLoss();
                if (stopLoss == null) {
                    stopLoss = request.getEntryPrice();
                    request.setStopLoss(0.05); // dummy SL? No, keep original logic
                }
                double lossPerShare = Math.abs(request.getEntryPrice() - stopLoss);
                if (lossPerShare == 0) {
                    request.setQuantity(0);
                } else {
                    if (isNoStrikeExchange) {
                        int quantityAsPerLoss = (int) (maxLossPerTrade / lossPerShare);
                        int quantityAsPerMaxAmt = (int) (maxAmtPerTrade / request.getEntryPrice());
                        int quantity = Math.min(quantityAsPerLoss, quantityAsPerMaxAmt);
                        if (quantity < 0) quantity = 0;
                        request.setQuantity(quantity);
                    } else {
                        int lotSizeForCalc = script.getLotSize() != null ? script.getLotSize() : 1;
                        int lossPerLot = (int) (lossPerShare * lotSizeForCalc);
                        if (lossPerLot == 0) {
                            request.setQuantity(0);
                        } else {
                            int quantityAsPerLoss = maxLossPerTrade / lossPerLot;
                            int quantityAsPerMaxAmt = (int) (maxAmtPerTrade / (request.getEntryPrice() * lotSizeForCalc));
                            int quantity = Math.min(quantityAsPerLoss, quantityAsPerMaxAmt);
                            if (quantity < 0) quantity = 0;
                            request.setQuantity(quantity);
                        }
                    }
                }
            }
        }

        int lotSize = script.getLotSize() != null ? script.getLotSize() : 1;
        long finalQuantity;
        if (isNoStrikeExchange) {
            finalQuantity = request.getQuantity() != null ? request.getQuantity().longValue() : 0L;
        } else {
            finalQuantity = (long) request.getQuantity() * lotSize;
        }

        if (finalQuantity <= 0L) {
            throw new InvalidTradeRequestException("Quantity must be greater than zero");
        }
        
        // Resolve spot scrip code if needed
        Integer spotScripCode = request.getSpotScripCode();
        boolean useSpotForEntry = Boolean.TRUE.equals(request.getUseSpotForEntry());
        boolean useSpotForSl = Boolean.TRUE.equals(request.getUseSpotForSl());
        boolean useSpotForTarget = Boolean.TRUE.equals(request.getUseSpotForTarget());
        
        // If the legacy useSpotPrice flag is set, enable all granular flags
        if (Boolean.TRUE.equals(request.getUseSpotPrice())) {
            useSpotForEntry = true;
            useSpotForSl = true;
            useSpotForTarget = true;
        }

        // If it's an option (has an option type), always try to resolve spot scrip code.
        if (hasOptionType(request.getOptionType()) && spotScripCode == null) {
            spotScripCode = resolveSpotScripCodeForOption(request.getInstrument());
        }

        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setSymbol(request.getInstrument());
        trade.setScripCode(script.getScripCode());
        trade.setExchange(script.getExchange());
        trade.setInstrumentType(script.getInstrumentType());
        trade.setStrikePrice(request.getStrikePrice());
        trade.setOptionType(request.getOptionType());
        trade.setExpiry(request.getExpiry());
        trade.setEntryPrice(request.getEntryPrice());
        trade.setStopLoss(request.getStopLoss());
        trade.setTarget1(request.getTarget1());
        trade.setTarget2(request.getTarget2());
        trade.setTarget3(request.getTarget3());
        trade.setTrailingSl(request.getTrailingSl());
        trade.setQuantity(finalQuantity);
        trade.setLots(request.getQuantity()); // Store the lots
        trade.setTslEnabled(request.getTslEnabled()); // Store TSL flag
        trade.setStatus(TriggeredTradeStatus.EXECUTED); // Mark as EXECUTED immediately
        trade.setTriggeredAt(LocalDateTime.now());
        trade.setEntryAt(LocalDateTime.now());
        trade.setIntraday(request.getIntraday());
        trade.setBrokerCredentialsId(request.getBrokerCredentialsId());
        trade.setAppUserId(request.getUserId());
        trade.setUseSpotForEntry(useSpotForEntry);
        trade.setUseSpotForSl(useSpotForSl);
        trade.setUseSpotForTarget(useSpotForTarget);
        trade.setUseSpotPrice(request.getUseSpotPrice()); // Store legacy flag
        trade.setSpotScripCode(spotScripCode);
        trade.setSource(request.getSource()); // Copy source
        // Set a dummy orderId to indicate manual entry, or leave null?
        // If null, polling service might be confused. Better to set a marker.
        trade.setOrderId("MANUAL-" + System.currentTimeMillis());

        TriggeredTradeSetupEntity saved = triggeredTradeRepo.save(trade);

        // Start monitoring
        String key = trade.getExchange() + trade.getScripCode();
        webSocketSubscriptionService.subscribeToScrip(key);
        
        // Always subscribe to spot scrip if it exists for an option trade
        if (trade.getSpotScripCode() != null) {
            ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(trade.getSpotScripCode());
            if (spotScript != null) {
                subscribeToSpotFeed(spotScript);
            }
        }

        handleEntryOrderExecution(saved);
        saved = triggeredTradeRepo.findById(saved.getId()).orElse(saved);

        log.info("✅ Manually added executed trade: {}", saved.getId());
        return saved;
    }

    public boolean moveStopLossToCost(Long tradeId) {
        TriggeredTradeSetupEntity tradeSetupEntity = triggeredTradeRepo.findById(tradeId).orElse(null);
        if (tradeSetupEntity == null) return false;

        Double costPrice = tradeSetupEntity.getActualEntryPrice() != null && tradeSetupEntity.getActualEntryPrice() > 0d
                ? tradeSetupEntity.getActualEntryPrice()
                : tradeSetupEntity.getEntryPrice();
        tradeSetupEntity.setStopLoss(costPrice);
        tradeSetupEntity.setUseSpotForSl(Boolean.FALSE);
        triggeredTradeRepo.save(tradeSetupEntity);
        return true;
    }

    public TriggeredTradeSetupEntity execute(TriggeredTradeSetupEntity trigger, double ltp) {
        // Backwards-compatible execute: record triggeredAt as now
        return execute(trigger, ltp, java.time.LocalDateTime.now());
    }

    // New overload: allow caller to pass exact triggeredAt (time when entry condition met)
    public TriggeredTradeSetupEntity execute(TriggeredTradeSetupEntity trigger, double ltp, java.time.LocalDateTime triggeredAt) {
        return execute(trigger, ltp, triggeredAt, false);
    }

    private TriggeredTradeSetupEntity execute(TriggeredTradeSetupEntity trigger,
                                             double ltp,
                                             java.time.LocalDateTime triggeredAt,
                                             boolean chaseEntryUntilExecuted) {
        String lockKey = buildEntryLockKey(trigger);
        try {
            return orderPlacementGuard.withLock(lockKey, ORDER_LOCK_TIMEOUT,
                    () -> executeWithoutLock(trigger, ltp, triggeredAt, chaseEntryUntilExecuted));
        } catch (OrderPlacementGuard.LockAcquisitionException e) {
            log.warn("⚠️ Duplicate entry placement prevented for triggerId={}, symbol={}: {}",
                    triggerLogId(trigger),
                    trigger != null ? trigger.getSymbol() : "N/A",
                    e.getMessage());
            return null;
        }
    }

    private TriggeredTradeSetupEntity executeWithoutLock(TriggeredTradeSetupEntity trigger, double ltp, java.time.LocalDateTime triggeredAt) {
        return executeWithoutLock(trigger, ltp, triggeredAt, false);
    }

    private TriggeredTradeSetupEntity executeWithoutLock(TriggeredTradeSetupEntity trigger,
                                                        double ltp,
                                                        java.time.LocalDateTime triggeredAt,
                                                        boolean chaseEntryUntilExecuted) {
        try {
            // Safety guard: do not attempt broker placement if quantity is null/zero
            if (trigger.getQuantity() == null || trigger.getQuantity() <= 0L) {
                log.warn("Blocking placement for trigger {} due to non-positive quantity: {}", triggerLogId(trigger), trigger.getQuantity());
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                rejected.setTriggerRequestId(trigger.getTriggerRequestId());
                rejected.setStatus(TriggeredTradeStatus.REJECTED);
                rejected.setExitReason("Quantity must be greater than zero");
                rejected.setScripCode(trigger.getScripCode());
                rejected.setExchange(trigger.getExchange());
                rejected.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
                rejected.setAppUserId(trigger.getAppUserId());
                rejected.setSymbol(trigger.getSymbol());
                rejected.setExpiry(trigger.getExpiry());
                rejected.setStrikePrice(trigger.getStrikePrice());
                rejected.setStopLoss(trigger.getStopLoss());
                rejected.setTarget1(trigger.getTarget1());
                rejected.setTarget2(trigger.getTarget2());
                rejected.setQuantity(trigger.getQuantity());
                rejected.setLots(trigger.getLots()); // Pass lots
                rejected.setTslEnabled(trigger.getTslEnabled()); // Pass TSL flag
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
                rejected.setSource(trigger.getSource());
                try { triggeredTradeRepo.save(rejected); } catch (Exception ignore) { }
                try {
                    String title = "Order Rejected ❌";
                    StringBuilder body = new StringBuilder();
                    body.append("Instrument: ").append(trigger.getSymbol());
                    if (trigger.getStrikePrice() != null) body.append(" ").append(trigger.getStrikePrice());
                    if (trigger.getOptionType() != null) body.append(" ").append(trigger.getOptionType());
                    body.append("\nReason: Quantity must be greater than zero");
                    telegramNotificationService.sendTradeMessageForUser(trigger.getAppUserId(), title, body.toString());
                } catch (Exception e) { log.warn("Failed to send telegram for zero-qty rejection: {}", e.getMessage()); }
                TradeEventLogger.logOrderRejected("ENTRY", trigger, "Quantity must be greater than zero", ltp);
                return rejected;
            }

            // Check for price difference > 8%
//            if (trigger.getEntryPrice() != null && trigger.getEntryPrice() > 0) {
//                double priceDiff = Math.abs(ltp - trigger.getEntryPrice());
//                double percentageDiff = (priceDiff / trigger.getEntryPrice()) * 100;
//
//                if (percentageDiff > 20) {
//                    log.warn("Blocking placement for trigger {} due to price difference > 8%: EntryPrice={}, LTP={}, Diff%={}",
//                            trigger.getId(), trigger.getEntryPrice(), ltp, percentageDiff);
//                    TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
//                    rejected.setStatus(TriggeredTradeStatus.REJECTED);
//                    rejected.setExitReason("Price difference > 8% (Slippage protection)");
//                    rejected.setScripCode(trigger.getScripCode());
//                    rejected.setExchange(trigger.getExchange());
//                    rejected.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
//                    rejected.setAppUserId(trigger.getAppUserId());
//                    rejected.setSymbol(trigger.getSymbol());
//                    rejected.setExpiry(trigger.getExpiry());
//                    rejected.setStrikePrice(trigger.getStrikePrice());
//                    rejected.setStopLoss(trigger.getStopLoss());
//                    rejected.setTarget1(trigger.getTarget1());
//                    rejected.setTarget2(trigger.getTarget2());
//                    rejected.setQuantity(trigger.getQuantity());
//                    rejected.setLots(trigger.getLots());
//                    rejected.setTslEnabled(trigger.getTslEnabled());
//                    rejected.setTarget3(trigger.getTarget3());
//                    rejected.setInstrumentType(trigger.getInstrumentType());
//                    rejected.setEntryPrice(ltp);
//                    rejected.setOptionType(trigger.getOptionType());
//                    rejected.setIntraday(trigger.getIntraday());
//                    try { triggeredTradeRepo.save(rejected); } catch (Exception ignore) { }
//                    try {
//                        String title = "Order Rejected ❌";
//                        StringBuilder body = new StringBuilder();
//                        body.append("Instrument: ").append(trigger.getSymbol());
//                        if (trigger.getStrikePrice() != null) body.append(" ").append(trigger.getStrikePrice());
//                        if (trigger.getOptionType() != null) body.append(" ").append(trigger.getOptionType());
//                        body.append("\nReason: Price difference is more than 8%");
//                        body.append("\nExpected: ").append(trigger.getEntryPrice());
//                        body.append("\nActual: ").append(ltp);
//                        telegramNotificationService.sendTradeMessageForUser(trigger.getAppUserId(), title, body.toString());
//                    } catch (Exception e) { log.warn("Failed to send telegram for price diff rejection: {}", e.getMessage()); }
//                    return rejected;
//                }
//            }

            BrokerContext ctx = resolveBrokerContext(trigger.getBrokerCredentialsId(), trigger.getAppUserId());
            if (!isBrokerContextUsable(ctx)) {
                throw new IllegalStateException("No active broker configured for this user");
            }

            BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
            if (brokerService == null) {
                throw new IllegalStateException("No broker service found for: " + ctx.getBrokerName());
            }

            OrderPlacementResult result = attemptEntryPlacement(trigger, ltp, ctx, brokerService, chaseEntryUntilExecuted);

            if (!result.isSuccess()) {
                TradeEventLogger.logOrderRejected("ENTRY", trigger, result.getRejectionReason(), ltp, result.getAttemptedPrice());
                log.error("❌ Place order failed for trigger {}. Reason: {}", triggerLogId(trigger), result.getRejectionReason());
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                rejected.setTriggerRequestId(trigger.getTriggerRequestId());
                rejected.setStatus(TriggeredTradeStatus.REJECTED);
                rejected.setExitReason(result.getRejectionReason());
                rejected.setScripCode(trigger.getScripCode());
                rejected.setExchange(trigger.getExchange());
                rejected.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
                rejected.setAppUserId(trigger.getAppUserId());
                rejected.setSymbol(trigger.getSymbol());
                rejected.setExpiry(trigger.getExpiry());
                rejected.setStrikePrice(trigger.getStrikePrice());
                rejected.setStopLoss(trigger.getStopLoss());
                rejected.setTarget1(trigger.getTarget1());
                rejected.setTarget2(trigger.getTarget2());
                rejected.setQuantity(trigger.getQuantity());
                rejected.setLots(trigger.getLots()); // Pass lots
                rejected.setTslEnabled(trigger.getTslEnabled()); // Pass TSL flag
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
                rejected.setSource(trigger.getSource());
                if (isUsableBrokerOrderId(result.getOrderId())) {
                    rejected.setOrderId(result.getOrderId());
                }
                try {
                    triggeredTradeRepo.save(rejected);
                    webSocketSubscriptionService.unsubscribeFromScrip(trigger.getExchange() + trigger.getScripCode());
                } catch (Exception e) {
                    log.warn("Failed to persist rejected triggered trade after placeOrder failures: {}", e.getMessage());
                }

                try {
                    String title = "Order Placement Failed ❌";
                    StringBuilder body = new StringBuilder();
                    body.append("Instrument: ").append(trigger.getSymbol()).append("\n");
                    body.append("Exchange: ").append(trigger.getExchange()).append("\n");
                    body.append("Attempted Qty: ").append(trigger.getQuantity()).append("\n");
                    body.append("Attempted Price(LTP): ").append(ltp).append("\n");
                    body.append("Trigger Entity Id: ").append(triggerLogId(trigger)).append("\n");
                    body.append("Reason: ").append(result.getRejectionReason());
                    telegramNotificationService.sendTradeMessageForUser(trigger.getAppUserId(), title, body.toString());
                } catch (Exception e) {
                    log.warn("Failed sending telegram notification for placeOrder failure: {}", e.getMessage());
                }

                return rejected;
            }

            // Order placed successfully
            TradeEventLogger.logOrderAccepted("ENTRY", trigger, result, ltp);
            log.info("✅ Order placed successfully: orderId={}", result.getOrderId());

            TriggeredTradeSetupEntity triggeredTradeSetupEntity = new TriggeredTradeSetupEntity();
            triggeredTradeSetupEntity.setTriggerRequestId(trigger.getTriggerRequestId());
            triggeredTradeSetupEntity.setOrderId(result.getOrderId());
            triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
            triggeredTradeSetupEntity.setTriggeredAt(triggeredAt != null ? triggeredAt : java.time.LocalDateTime.now());

            triggeredTradeSetupEntity.setScripCode(trigger.getScripCode());
            triggeredTradeSetupEntity.setExchange(trigger.getExchange());
            triggeredTradeSetupEntity.setSymbol(trigger.getSymbol());
            triggeredTradeSetupEntity.setExpiry(trigger.getExpiry());
            triggeredTradeSetupEntity.setStrikePrice(trigger.getStrikePrice());
            triggeredTradeSetupEntity.setStopLoss(trigger.getStopLoss());
            triggeredTradeSetupEntity.setTarget1(trigger.getTarget1());
            triggeredTradeSetupEntity.setTarget2(trigger.getTarget2());
            triggeredTradeSetupEntity.setQuantity(trigger.getQuantity());
            triggeredTradeSetupEntity.setLots(trigger.getLots()); // Pass lots
            triggeredTradeSetupEntity.setTslEnabled(trigger.getTslEnabled()); // Pass TSL flag
            triggeredTradeSetupEntity.setTarget3(trigger.getTarget3());
            triggeredTradeSetupEntity.setInstrumentType(trigger.getInstrumentType());
            triggeredTradeSetupEntity.setEntryPrice(trigger.getEntryPrice());
            triggeredTradeSetupEntity.setOptionType(trigger.getOptionType());
            triggeredTradeSetupEntity.setIntraday(trigger.getIntraday());
            triggeredTradeSetupEntity.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
            triggeredTradeSetupEntity.setAppUserId(trigger.getAppUserId());
            triggeredTradeSetupEntity.setUseSpotForEntry(trigger.getUseSpotForEntry());
            triggeredTradeSetupEntity.setUseSpotForSl(trigger.getUseSpotForSl());
            triggeredTradeSetupEntity.setUseSpotForTarget(trigger.getUseSpotForTarget());
            triggeredTradeSetupEntity.setUseSpotPrice(trigger.getUseSpotPrice()); // Store legacy flag
            triggeredTradeSetupEntity.setSpotScripCode(trigger.getSpotScripCode());
            triggeredTradeSetupEntity.setSource(trigger.getSource());
            
            triggeredTradeSetupEntity = triggeredTradeRepo.save(triggeredTradeSetupEntity);

            // If broker returned immediate execution details (e.g. Simulator or fast market order)
            if ("Fully Executed".equalsIgnoreCase(result.getStatus())) {
                triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.EXECUTED);
                triggeredTradeSetupEntity.setEntryAt(LocalDateTime.now());
                if (result.getExecutedPrice() != null) {
                    Double originalEntryPrice = triggeredTradeSetupEntity.getEntryPrice();
                    Double executedPrice = result.getExecutedPrice();
                    
                    triggeredTradeSetupEntity.setActualEntryPrice(executedPrice);

                    // Robust check for spot entry
                    boolean isSpotEntry = Boolean.TRUE.equals(triggeredTradeSetupEntity.getUseSpotForEntry()) 
                            || (triggeredTradeSetupEntity.getUseSpotForEntry() == null && Boolean.TRUE.equals(triggeredTradeSetupEntity.getUseSpotPrice()));
                    boolean isSharekhanSource = isSharekhanSource(triggeredTradeSetupEntity.getSource());

                    // Sharekhan signals carry source-defined entry/SL/target levels, so keep them unchanged after fill.
                    if (!isSpotEntry && !isSharekhanSource) {
                        if (originalEntryPrice != null && executedPrice != null) {
                            double diff = executedPrice - originalEntryPrice;
                            if (Math.abs(diff) > 0.0001) {
                                log.info("Adjusting SL and Targets for trade {} due to immediate execution price difference: {}. UseSpotForEntry={}, OriginalEntry={}, Executed={}", 
                                    triggeredTradeSetupEntity.getId(), diff, isSpotEntry, originalEntryPrice, executedPrice);
                                if (triggeredTradeSetupEntity.getStopLoss() != null) {
                                    triggeredTradeSetupEntity.setStopLoss(triggeredTradeSetupEntity.getStopLoss() + diff);
                                }
                                if (triggeredTradeSetupEntity.getTarget1() != null) {
                                    triggeredTradeSetupEntity.setTarget1(triggeredTradeSetupEntity.getTarget1() + diff);
                                }
                                if (triggeredTradeSetupEntity.getTarget2() != null) {
                                    triggeredTradeSetupEntity.setTarget2(triggeredTradeSetupEntity.getTarget2() + diff);
                                }
                                if (triggeredTradeSetupEntity.getTarget3() != null) {
                                    triggeredTradeSetupEntity.setTarget3(triggeredTradeSetupEntity.getTarget3() + diff);
                                }
                            }
                        }
                        triggeredTradeSetupEntity.setEntryPrice(executedPrice);
                    } else if (isSharekhanSource) {
                        log.info("Preserving Sharekhan source entry/SL/targets for trade {} after immediate execution. OriginalEntry={}, Executed={}",
                                triggeredTradeSetupEntity.getId(), originalEntryPrice, executedPrice);
                    }
                }
                triggeredTradeSetupEntity = triggeredTradeRepo.save(triggeredTradeSetupEntity);
                TradeEventLogger.logOrderExecuted("ENTRY", triggeredTradeSetupEntity, result.getExecutedPrice(), result.getStatus());
                log.info("✅ Trade executed immediately. Skipping order status polling.");
                handleEntryOrderExecution(triggeredTradeSetupEntity);
                return triggeredTradeSetupEntity;
            }

            // Publish event AFTER transaction commit so pollers/readers see the committed DB state
            final TriggeredTradeSetupEntity finalEntity = triggeredTradeSetupEntity;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishEvent(new OrderPlacedEvent(finalEntity));
                    }
                });
            } else {
                eventPublisher.publishEvent(new OrderPlacedEvent(finalEntity));
            }

            log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trigger.getScripCode(), ltp);
            if (chaseEntryUntilExecuted) {
                scheduleEntryOrderChase(triggeredTradeSetupEntity);
            }
            if (triggeredTradeSetupEntity.getExitOrderId() != null) {
                scheduleExitOrderChase(triggeredTradeSetupEntity);
            }
            return triggeredTradeSetupEntity;

        } catch (Exception e) {
            log.error("❌ Error executing trade for trigger {}: {}", triggerLogId(trigger), e.getMessage(), e);
        }
        return null;
    }

    public void markOrderRejected(String orderId) {
        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Trade not found"));

        trade.setStatus(TriggeredTradeStatus.REJECTED);
        stopEntryOrderChase(trade.getId());
        triggeredTradeRepo.save(trade);
        webSocketSubscriptionService.unsubscribeFromScrip(trade.getExchange() + trade.getScripCode());

        log.warn("❌ Order rejected for orderId {}", orderId);

        try {
            String title = "Order Rejected ❌";
            StringBuilder body = new StringBuilder();
            body.append("Instrument: ").append(trade.getSymbol());
            if (trade.getStrikePrice() != null) body.append(" ").append(trade.getStrikePrice());
            if (trade.getOptionType() != null) body.append(" ").append(trade.getOptionType());
            body.append("\nOrderId: ").append(orderId);
            body.append("\nStatus: ").append(trade.getStatus());
            if (trade.getExitReason() != null) body.append("\nReason: ").append(trade.getExitReason());
            telegramNotificationService.sendTradeMessageForUser(trade.getAppUserId(), title, body.toString());
        } catch (Exception e) {
            log.warn("Failed sending telegram notification for rejection in execute(): {}", e.getMessage());
        }
    }

    public boolean forceCloseByScripCode(int scripCode) {
        return forceCloseByScripCode(scripCode, null);
    }

    public boolean forceCloseByScripCode(int scripCode, Double price) {
        java.util.List<TriggeredTradeSetupEntity> trades = triggeredTradeRepo.findByScripCodeAndStatusIn(
                scripCode,
                java.util.List.of(TriggeredTradeStatus.EXECUTED, TriggeredTradeStatus.TARGET_ORDER_PLACED)
        );

        if (trades != null && !trades.isEmpty()) {
            TriggeredTradeSetupEntity trade = trades.get(0);

            double exitPrice;
            if (price != null) {
                exitPrice = price;
            } else {
                Double ltp = ltpCacheService.getLtp(scripCode);
                exitPrice = (ltp != null) ? ltp : trade.getEntryPrice(); // fallback to entry
            }

            log.info("🛑 Force-closing trade {} at price: {}", trade.getId(), exitPrice);

            // delegate directly to OrderExitService to avoid self-invocation of transactional logic
            squareOff(trade, exitPrice, "Force fully close the trade"); // reuse existing logic
            return true;
        } else {
            return false;
        }
    }

    public void squareOff(TriggeredTradeSetupEntity trade, double exitPrice, String exitReason) {
        squareOff(trade, exitPrice, exitReason, TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }

    public void squareOff(TriggeredTradeSetupEntity trade,
                          double exitPrice,
                          String exitReason,
                          TriggeredTradeStatus placedStatus) {
        if (placedStatus != TriggeredTradeStatus.EXIT_ORDER_PLACED
                && placedStatus != TriggeredTradeStatus.TARGET_ORDER_PLACED) {
            throw new IllegalArgumentException("placedStatus must be EXIT_ORDER_PLACED or TARGET_ORDER_PLACED");
        }

        // Re-load the trade from DB (within transaction) to avoid races. Claim the trade by setting status
        TriggeredTradeSetupEntity persisted = triggeredTradeRepo.findById(trade.getId())
                .orElseThrow(() -> new RuntimeException("Trade not found: " + trade.getId()));

        Double sanitizedRequestedExitPrice = sanitizeExitPriceCandidate(persisted, exitPrice, "SQUARE_OFF_REQUEST");
        if (sanitizedRequestedExitPrice == null) {
            log.warn("Square-off delayed for trade {} because exitPrice={} is implausible for this instrument. Waiting for a valid traded option price.",
                    persisted.getId(), exitPrice);
            return;
        }

        if (!hasUsableTradedExitPrice(persisted, sanitizedRequestedExitPrice)) {
            log.warn("Square-off delayed for trade {} because exitPrice={} looks like spot, not the traded option LTP. Waiting for correct option LTP.",
                    persisted.getId(), sanitizedRequestedExitPrice);
            return;
        }

        // Prefer stricter claim: if current status is EXIT_TRIGGERED then move to the requested exit-order state.
        // Otherwise (e.g., forceClose) allow transition from EXECUTED into the requested exit-order state.
        int claimed = 0;
        try {
            // Try each claimable status in order, so TARGET_ORDER_PLACED trades can transition into EXIT_ORDER_PLACED.
            TriggeredTradeStatus[] claimableStatuses = new TriggeredTradeStatus[] {
                    TriggeredTradeStatus.EXIT_TRIGGERED,
                    TriggeredTradeStatus.EXECUTED,
                    TriggeredTradeStatus.TARGET_ORDER_PLACED
            };

            for (TriggeredTradeStatus fromStatus : claimableStatuses) {
                if (claimed > 0) {
                    break;
                }

                claimed = triggeredTradeRepo.claimIfStatusEquals(
                        persisted.getId(),
                        fromStatus.name(),
                        placedStatus.name(),
                        exitReason
                );
            }
        } catch (Exception e) {
            log.warn("Failed to claim exit for trade {}: {}", persisted.getId(), e.getMessage());
        }

        if (claimed == 0) {
            // someone else already claimed or trade in terminal state
            log.info("⚠️ Square-off skipped: trade {} could not be claimed for exit (already claimed/terminal)", persisted.getId());
            return;
        }

        // Persist exitReason so we record why we initiated a square-off. Do not finalize the placed state until we have an exitOrderId.
        try {
            persisted.setExitReason(exitReason);
            triggeredTradeRepo.save(persisted);
        } catch (Exception e) {
            log.debug("Failed to persist exitReason for trade {}: {}", persisted.getId(), e.getMessage());
        }

        // reload the persisted state after claim to ensure we have latest values
        persisted = triggeredTradeRepo.findById(trade.getId())
                .orElseThrow(() -> new RuntimeException("Trade not found after claim: " + trade.getId()));

        ExitDiagnostics exitDiagnostics = analyseExit(persisted, sanitizedRequestedExitPrice, exitReason);
        logExitDiagnostics(persisted, exitDiagnostics, sanitizedRequestedExitPrice);

        Double resolvedExitPrice = exitDiagnostics.recommendedLimit() != null
                ? exitDiagnostics.recommendedLimit()
                : sanitizedRequestedExitPrice;
        resolvedExitPrice = sanitizeExitPriceCandidate(persisted, resolvedExitPrice, "SQUARE_OFF_RESOLVED");
        if (resolvedExitPrice == null) {
            log.warn("Square-off delayed for trade {} because resolved exit price is implausible. trigger={} requested={}",
                    persisted.getId(),
                    formatPrice(exitDiagnostics.triggerPrice()),
                    formatPrice(sanitizedRequestedExitPrice));
            return;
        }

        // Resolve customerId from broker credentials if available; fallback to default
        OrderPlacementResult result = placeExitOrderAndPersist(persisted, resolvedExitPrice, exitReason, placedStatus);

        if (result == null || !result.isSuccess()) {
            String rejectionReason = result != null ? result.getRejectionReason() : "Unknown error";
            if (result == null) {
                TradeEventLogger.logOrderRejected("EXIT", persisted, rejectionReason, sanitizedRequestedExitPrice);
            }
            log.error("❌ Exit place order failed for trade {}. Reason: {}", persisted.getId(), rejectionReason);
            try {
                persisted.setStatus(TriggeredTradeStatus.EXIT_FAILED);
                triggeredTradeRepo.save(persisted);
                webSocketSubscriptionService.unsubscribeFromScrip(persisted.getExchange() + persisted.getScripCode());
            } catch (Exception e) {
                log.warn("Failed to persist EXIT_FAILED state after exit placeOrder failures: {}", e.getMessage());
            }

            stopExitOrderChase(persisted.getId());

            try {
                String title = "Exit Order Placement Failed ❌";
                StringBuilder body = new StringBuilder();
                body.append("Instrument: ").append(persisted.getSymbol()).append("\n");
                body.append("Exchange: ").append(persisted.getExchange()).append("\n");
                body.append("Attempted Qty: ").append(persisted.getQuantity()).append("\n");
                body.append("Attempted Price: ").append(sanitizedRequestedExitPrice).append("\n");
                body.append("TradeId: ").append(persisted.getId()).append("\n");
                body.append("Reason: ").append(rejectionReason);
                telegramNotificationService.sendTradeMessageForUser(persisted.getAppUserId(), title, body.toString());
            } catch (Exception e) {
                log.warn("Failed sending telegram notification for exit placeOrder failure: {}", e.getMessage());
            }

            return;
        }
        return;
    }


    private OrderPlacementResult attemptEntryPlacement(TriggeredTradeSetupEntity trigger,
                                                       double ltp,
                                                       BrokerContext ctx,
                                                       BrokerService brokerService) {
        return attemptEntryPlacement(trigger, ltp, ctx, brokerService, false);
    }

    private OrderPlacementResult attemptEntryPlacement(TriggeredTradeSetupEntity trigger,
                                                       double ltp,
                                                       BrokerContext ctx,
                                                       BrokerService brokerService,
                                                       boolean chaseEntryUntilExecuted) {
        OrderPlacementResult lastResult = null;
        String orderId = null;
        TradeStatus latestStatus = TradeStatus.NO_RECORDS;
        int maxAttempts = chaseEntryUntilExecuted ? 1 : MAX_ENTRY_ATTEMPTS;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0 && orderId != null && !orderId.isBlank()) {
                latestStatus = fetchEntryOrderStatus(trigger, ctx, orderId);
                log.info("📊 Entry status snapshot for trade {} attempt {}: {}", triggerLogId(trigger), attempt + 1, latestStatus);
                if (isOrderFilled(latestStatus)) {
                    log.info("✅ Entry order {} already filled before modify attempt", orderId);
                    return lastResult != null && lastResult.isSuccess()
                            ? lastResult
                            : OrderPlacementResult.builder()
                                    .success(true)
                                    .orderId(orderId)
                                    .status("Fully Executed")
                                    .build();
                }
                if (TradeStatus.REJECTED.equals(latestStatus)) {
                    log.warn("⚠️ Entry order {} rejected before modify attempt", orderId);
                    break;
                }
            }

            EntryDiagnostics entryDiagnostics = analyseEntry(trigger, ltp);
            logEntryDiagnostics(trigger, entryDiagnostics, ltp);

            if (!entryDiagnostics.shouldPlace() && !chaseEntryUntilExecuted) {
                log.info("🚫 Entry placement skipped for trigger {} reason={} spread={} bid={} ask={} mid={}",
                        triggerLogId(trigger),
                        entryDiagnostics.reason(),
                        formatPercent(entryDiagnostics.spreadPercent()),
                        formatPrice(entryDiagnostics.bestBid()),
                        formatPrice(entryDiagnostics.bestAsk()),
                        formatPrice(entryDiagnostics.recommendedLimit()));

                if (orderId != null && !orderId.isBlank()) {
                    String rejectionReason = "ENTRY_SPREAD_WIDENED_AFTER_PLACE";
                    if (brokerService instanceof ModifiableEntryBrokerService modifiableEntryBroker) {
                        modifiableEntryBroker.cancelEntryOrder(trigger, ctx, orderId);
                        log.info("🚫 Cancelled pending entry order {} for trigger {} because spread widened to {}",
                                orderId, triggerLogId(trigger), formatPercent(entryDiagnostics.spreadPercent()));
                        return OrderPlacementResult.builder()
                                .success(false)
                                .orderId(orderId)
                                .status("Cancelled")
                                .attemptedPrice(entryDiagnostics.recommendedLimit())
                                .rejectionReason(rejectionReason)
                                .build();
                    }

                    log.warn("⚠️ Entry order {} remains pending because broker service {} cannot cancel after spread widened",
                            orderId, brokerService.getClass().getSimpleName());
                    return OrderPlacementResult.builder()
                            .success(false)
                            .orderId(orderId)
                            .status("Pending")
                            .attemptedPrice(entryDiagnostics.recommendedLimit())
                            .rejectionReason(rejectionReason + "_CANCEL_UNSUPPORTED")
                            .build();
                }

                TradeEventLogger.logOrderRejected("ENTRY_SPREAD_CHECK", trigger, entryDiagnostics.reason(), ltp);
                return OrderPlacementResult.builder()
                        .success(false)
                        .status("Rejected")
                        .rejectionReason(entryDiagnostics.reason())
                        .build();
            }
            if (!entryDiagnostics.shouldPlace()) {
                log.info("🧵 Manual entry trigger overriding spread check for trigger {} reason={} spread={} bid={} ask={} mid={}",
                        triggerLogId(trigger),
                        entryDiagnostics.reason(),
                        formatPercent(entryDiagnostics.spreadPercent()),
                        formatPrice(entryDiagnostics.bestBid()),
                        formatPrice(entryDiagnostics.bestAsk()),
                        formatPrice(entryDiagnostics.recommendedLimit()));
            }

            double price = resolveEntryAttemptPrice(entryDiagnostics, attempt, ltp);
            log.info("🎯 Entry attempt {} for trigger {} at price {}", attempt + 1, triggerLogId(trigger), formatPrice(price));
            TradeEventLogger.logOrderAttempt("ENTRY", trigger, attempt + 1, attempt == 0 ? "PLACE" : "MODIFY", price, orderId);

            OrderPlacementResult result;
            if (attempt == 0) {
                result = brokerService.placeOrder(trigger, ctx, price);
                if (result != null && isUsableBrokerOrderId(result.getOrderId())) {
                    orderId = result.getOrderId();
                    trigger.setOrderId(orderId);
                }
            } else {
                if (orderId == null || orderId.isBlank()) {
                    log.warn("Cannot modify entry for trigger {} because no orderId was captured from the first attempt", triggerLogId(trigger));
                    break;
                }
                if (!(brokerService instanceof ModifiableEntryBrokerService modifiableEntryBroker)) {
                    log.warn("Broker service {} does not support entry modify; aborting attempts for trigger {}", brokerService.getClass().getSimpleName(), triggerLogId(trigger));
                    break;
                }
                result = modifiableEntryBroker.modifyEntryOrder(trigger, ctx, orderId, price);
            }

            lastResult = result;
            if (result != null && result.getAttemptedPrice() == null) {
                result.setAttemptedPrice(price);
            }

            if (result != null && isUsableBrokerOrderId(result.getOrderId())) {
                orderId = result.getOrderId();
                trigger.setOrderId(orderId);
            } else if (result != null && isUsableBrokerOrderId(orderId)) {
                result.setOrderId(orderId);
            }

            if (result != null && result.isSuccess()) {
                log.info("✅ Entry attempt {} succeeded for trigger {} orderId={}", attempt + 1, triggerLogId(trigger), orderId);

                if (isOrderPlacementFilled(result)) {
                    return result;
                }

                if (chaseEntryUntilExecuted) {
                    return result;
                }

                if (orderId != null && !orderId.isBlank()) {
                    try {
                        Thread.sleep(FINAL_STATUS_CHECK_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Entry status wait interrupted for trigger {}", triggerLogId(trigger));
                    }

                    latestStatus = fetchEntryOrderStatus(trigger, ctx, orderId);
                    log.info("📊 Entry status snapshot after attempt {} for trade {}: {}", attempt + 1, triggerLogId(trigger), latestStatus);

                    if (isOrderFilled(latestStatus)) {
                        return result;
                    }

                    if (attempt < maxAttempts - 1) {
                        log.info("⏱️ Entry order {} still pending after attempt {}. Preparing next attempt.", orderId, attempt + 1);
                        continue;
                    }

                    log.warn("⏱️ Entry order {} not filled after final attempt. Initiating cancellation.", orderId);
                    String cancelledOrderId = orderId;
                    if (brokerService instanceof ModifiableEntryBrokerService modifiableEntryBroker) {
                        modifiableEntryBroker.cancelEntryOrder(trigger, ctx, cancelledOrderId);
                        log.info("🚫 Cancelled entry order {} for trigger {} after pending final status", cancelledOrderId, triggerLogId(trigger));
                        latestStatus = fetchEntryOrderStatus(trigger, ctx, cancelledOrderId);
                        if (isOrderFilled(latestStatus)) {
                            log.warn("Entry order {} filled while cancellation was being confirmed; treating trigger {} as executed.",
                                    cancelledOrderId, triggerLogId(trigger));
                            return OrderPlacementResult.builder()
                                    .success(true)
                                    .orderId(cancelledOrderId)
                                    .status("Fully Executed")
                                    .build();
                        }
                        orderId = null;
                    } else {
                        log.warn("⚠️ Broker service {} cannot cancel pending entry order {}", brokerService.getClass().getSimpleName(), orderId);
                    }

                    lastResult = OrderPlacementResult.builder()
                            .success(false)
                            .orderId(cancelledOrderId)
                            .status("Cancelled")
                            .rejectionReason("ENTRY_NOT_FILLED_AFTER_RETRIES")
                            .build();
                    break;
                }

                return result;
            }

            String reason = result != null ? result.getRejectionReason() : "unknown";
            log.warn("⚠️ Entry attempt {} failed for trigger {} reason={}", attempt + 1, triggerLogId(trigger), reason);

            if (attempt < maxAttempts - 1) {
                if (orderId != null && !orderId.isBlank()) {
                    latestStatus = fetchEntryOrderStatus(trigger, ctx, orderId);
                    log.info("📊 Entry status snapshot post attempt {} for trade {}: {}", attempt + 1, triggerLogId(trigger), latestStatus);
                    if (isOrderFilled(latestStatus)) {
                        log.info("✅ Entry order {} filled after attempt {}", orderId, attempt + 1);
                        return lastResult != null && lastResult.isSuccess()
                                ? lastResult
                                : OrderPlacementResult.builder()
                                        .success(true)
                                        .orderId(orderId)
                                        .status("Fully Executed")
                                        .build();
                    }
                    if (TradeStatus.REJECTED.equals(latestStatus)) {
                        log.warn("⚠️ Entry order {} rejected after attempt {}", orderId, attempt + 1);
                        break;
                    }
                }

                long delayMillis = "OI".equalsIgnoreCase(trigger.getInstrumentType() != null ? trigger.getInstrumentType().trim() : "")
                        ? 1000L
                        : 2000L;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Entry attempts interrupted for trigger {}", triggerLogId(trigger));
                    break;
                }
            }
        }

        if (!chaseEntryUntilExecuted
                && brokerService instanceof ModifiableEntryBrokerService modifiableEntryBroker
                && orderId != null && !orderId.isBlank()
                && !isOrderFilled(latestStatus)) {
            log.info("🚫 Cancelled pending entry order {} for trigger {}", orderId, triggerLogId(trigger));
            modifiableEntryBroker.cancelEntryOrder(trigger, ctx, orderId);
        }

        log.warn("❌ Entry attempts exhausted for trigger {}. Marking as rejected.", triggerLogId(trigger));
        return lastResult != null ? lastResult : OrderPlacementResult.builder()
                .success(false)
                .status("Rejected")
                .rejectionReason("ENTRY_ATTEMPTS_EXHAUSTED")
                .build();
    }

    private TradeStatus fetchEntryOrderStatus(TriggeredTradeSetupEntity trade,
                                              BrokerContext ctx,
                                              String orderId) {
        return fetchOrderStatus(trade, ctx, orderId);
    }

    private TradeStatus fetchOrderStatus(TriggeredTradeSetupEntity trade,
                                         BrokerContext ctx,
                                         String orderId) {
        if (trade == null || ctx == null || orderId == null || orderId.isBlank()) {
            return TradeStatus.NO_RECORDS;
        }

        Broker broker;
        try {
            broker = Broker.fromDisplayName(ctx.getBrokerName());
        } catch (Exception ignore) {
            return TradeStatus.NO_RECORDS;
        }

        try {
            BrokerService brokerService = brokerServiceFactory != null ? brokerServiceFactory.getService(ctx.getBrokerName()) : null;
            if (!(brokerService instanceof OrderStatusBrokerService orderStatusBrokerService)) {
                return TradeStatus.NO_RECORDS;
            }
            JSONObject response = orderStatusBrokerService.fetchOrderStatus(trade, ctx, orderId);
            return evaluateOrderFinalStatus(trade, response);
        } catch (Exception e) {
            log.debug("Order status fetch failed for trade {} order {}: {}", trade.getId(), orderId, e.getMessage());
            return TradeStatus.NO_RECORDS;
        }
    }

    private boolean isOrderFilled(TradeStatus status) {
        return TradeStatus.FULLY_EXECUTED.equals(status);
    }

    private boolean isOrderPlacementFilled(OrderPlacementResult result) {
        return result != null
                && result.isSuccess()
                && ShareKhanOrderUtil.isFullyExecutedStatus(result.getStatus());
    }

    boolean isUsableBrokerOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        String normalized = orderId.trim();
        return !"0".equals(normalized)
                && !"NA".equalsIgnoreCase(normalized)
                && !"null".equalsIgnoreCase(normalized);
    }

    private void scheduleEntryOrderChase(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getId() == null || !isUsableBrokerOrderId(trade.getOrderId())) {
            return;
        }
        if (!TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(trade.getStatus())) {
            return;
        }

        BrokerContext ctx = resolveBrokerContext(trade.getBrokerCredentialsId(), trade.getAppUserId());
        if (ctx == null || ctx.getBrokerName() == null) {
            return;
        }

        BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
        if (!(brokerService instanceof ModifiableEntryBrokerService modifiableEntryBroker)) {
            log.debug("Skipping entry chase for trade {} because broker {} cannot modify entry orders",
                    trade.getId(), ctx.getBrokerName());
            return;
        }

        long tradeId = trade.getId();
        String orderId = trade.getOrderId();
        stopEntryOrderChase(tradeId);

        EntryChaseState state = new EntryChaseState();
        entryChaseStates.put(tradeId, state);

        long delayMillis = "OI".equalsIgnoreCase(trade.getInstrumentType() != null ? trade.getInstrumentType().trim() : "")
                ? 1000L
                : 2000L;

        Runnable task = () -> {
            try {
                TriggeredTradeSetupEntity latest = triggeredTradeRepo.findById(tradeId).orElse(null);
                if (latest == null || !TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(latest.getStatus())) {
                    stopEntryOrderChase(tradeId);
                    return;
                }

                String latestOrderId = latest.getOrderId();
                if (!isUsableBrokerOrderId(latestOrderId)) {
                    stopEntryOrderChase(tradeId);
                    return;
                }

                TradeStatus status = fetchEntryOrderStatus(latest, ctx, latestOrderId);
                if (isOrderFilled(status)) {
                    stopEntryOrderChase(tradeId);
                    return;
                }
                if (TradeStatus.REJECTED.equals(status)) {
                    log.warn("🧵 Entry chase stopping for trade {} — order rejected", tradeId);
                    stopEntryOrderChase(tradeId);
                    return;
                }

                EntryChaseState chaseState = entryChaseStates.computeIfAbsent(tradeId, id -> new EntryChaseState());
                Double candidatePrice = determineEntryChasePrice(latest, chaseState);
                if (candidatePrice == null || candidatePrice <= 0) {
                    return;
                }

                if (chaseState.lastPrice != null && Math.abs(chaseState.lastPrice - candidatePrice) < 0.01) {
                    return;
                }

                TradeEventLogger.logOrderAttempt("ENTRY_CHASE", latest, chaseState.modifyAttempts + 1, "MODIFY", candidatePrice, latestOrderId);
                OrderPlacementResult modifyResult = modifiableEntryBroker.modifyEntryOrder(latest, ctx, latestOrderId, candidatePrice);
                if (modifyResult != null && modifyResult.getAttemptedPrice() == null) {
                    modifyResult.setAttemptedPrice(candidatePrice);
                }

                if (modifyResult != null && modifyResult.isSuccess()) {
                    String updatedOrderId = modifyResult.getOrderId();
                    if (isUsableBrokerOrderId(updatedOrderId) && !updatedOrderId.equals(latestOrderId)) {
                        latest.setOrderId(updatedOrderId);
                        triggeredTradeRepo.save(latest);
                    }
                    chaseState.lastPrice = candidatePrice;
                    chaseState.modifyAttempts++;
                    log.info("🧵 Entry chase adjusting trade {} order {} to {}",
                            tradeId, updatedOrderId != null ? updatedOrderId : latestOrderId, formatPrice(candidatePrice));

                    if (isOrderPlacementFilled(modifyResult)) {
                        stopEntryOrderChase(tradeId);
                    }
                } else {
                    log.warn("⚠️ Entry chase modify failed for trade {}: {}",
                            tradeId,
                            modifyResult != null ? modifyResult.getRejectionReason() : "No response");
                    stopEntryOrderChase(tradeId);
                }
            } catch (Exception e) {
                log.warn("⚠️ Entry chase iteration failed for trade {}: {}", tradeId, e.getMessage());
            }
        };

        ScheduledFuture<?> future = entryChaseScheduler.scheduleWithFixedDelay(task, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        entryChaseFutures.put(tradeId, future);
        log.info("🧵 Entry chase started for trade {} order {}", tradeId, orderId);
    }

    public void stopEntryOrderChase(Long tradeId) {
        if (tradeId == null) {
            return;
        }
        ScheduledFuture<?> future = entryChaseFutures.remove(tradeId);
        if (future != null) {
            future.cancel(true);
            log.info("🧵 Entry chase completed for trade {}", tradeId);
        }
        entryChaseStates.remove(tradeId);
    }

    boolean isEntryOrderChaseActive(Long tradeId) {
        return tradeId != null && entryChaseFutures.containsKey(tradeId);
    }

    private Double determineEntryChasePrice(TriggeredTradeSetupEntity trade, EntryChaseState state) {
        if (trade == null) {
            return null;
        }

        Double fallbackLtp = null;
        if (ltpCacheService != null && trade.getScripCode() != null) {
            fallbackLtp = ltpCacheService.getLtp(trade.getScripCode());
        }
        if (fallbackLtp == null || fallbackLtp <= 0) {
            fallbackLtp = trade.getEntryPrice();
        }
        if (fallbackLtp == null || fallbackLtp <= 0) {
            return null;
        }

        EntryDiagnostics diagnostics = analyseEntry(trade, fallbackLtp);
        if (!diagnostics.shouldPlace()) {
            log.info("🧵 Entry chase continuing despite spread check for trade {} reason={} spread={} bid={} ask={} mid={}",
                    trade.getId(),
                    diagnostics.reason(),
                    formatPercent(diagnostics.spreadPercent()),
                    formatPrice(diagnostics.bestBid()),
                    formatPrice(diagnostics.bestAsk()),
                    formatPrice(diagnostics.recommendedLimit()));
        }

        int attemptIndex = Math.min(state.modifyAttempts + 1, MAX_ENTRY_ATTEMPTS - 1);
        return resolveEntryAttemptPrice(diagnostics, attemptIndex, fallbackLtp);
    }

    private void scheduleExitOrderChase(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getId() == null) {
            return;
        }

        TriggeredTradeSetupEntity latestTrade = triggeredTradeRepo.findById(trade.getId()).orElse(trade);
        if (latestTrade.getExitOrderId() == null || latestTrade.getExitOrderId().isBlank()) {
            return;
        }

        if (!TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(latestTrade.getStatus())) {
            log.debug("Skipping exit chase for trade {} in status {}", latestTrade.getId(), latestTrade.getStatus());
            return;
        }

        BrokerContext ctx = resolveBrokerContext(latestTrade.getBrokerCredentialsId(), latestTrade.getAppUserId());
        if (ctx == null || ctx.getBrokerName() == null) {
            return;
        }

        Broker broker;
        try {
            broker = Broker.fromDisplayName(ctx.getBrokerName());
        } catch (Exception ignore) {
            return;
        }

        BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
        if (broker != Broker.SHAREKHAN && !(brokerService instanceof ModifiableEntryBrokerService)) {
            return;
        }

        long tradeId = latestTrade.getId();
        stopExitOrderChase(tradeId);

        ExitChaseState state = new ExitChaseState();
        exitChaseStates.put(tradeId, state);

        String exitOrderId = latestTrade.getExitOrderId();
        long delayMillis = "OI".equalsIgnoreCase(latestTrade.getInstrumentType() != null ? latestTrade.getInstrumentType().trim() : "")
                ? 750L
                : 1500L;

        Runnable task = () -> {
            try {
                TriggeredTradeSetupEntity latest = triggeredTradeRepo.findById(tradeId).orElse(null);
                if (latest == null || latest.getExitOrderId() == null) {
                    stopExitOrderChase(tradeId);
                    return;
                }

                TradeStatus status = fetchOrderStatus(latest, ctx, exitOrderId);
                if (isOrderFilled(status)) {
                    stopExitOrderChase(tradeId);
                    return;
                }
                if (TradeStatus.REJECTED.equals(status)) {
                    log.warn("🧵 Exit chase stopping for trade {} — order rejected", tradeId);
                    stopExitOrderChase(tradeId);
                    return;
                }

                ExitChaseState chaseState = exitChaseStates.computeIfAbsent(tradeId, id -> new ExitChaseState());
                Double candidatePrice = determineExitChasePrice(latest, chaseState);
                if (candidatePrice == null || candidatePrice <= 0) {
                    return;
                }

                if (chaseState.lastPrice != null && Math.abs(chaseState.lastPrice - candidatePrice) < 0.01) {
                    return;
                }

                ModifyExitOrderResult modifyResult = modifyExistingExitOrder(latest, candidatePrice, "EXIT_CHASE", TriggeredTradeStatus.EXIT_ORDER_PLACED);
                if (modifyResult.isSuccess()) {
                    chaseState.lastPrice = candidatePrice;
                    log.info("🧵 Exit chase adjusting trade {} order {} to {}", tradeId, exitOrderId, formatPrice(candidatePrice));
                } else {
                    log.warn("⚠️ Exit chase modify failed for trade {}: {}", tradeId, modifyResult.getMessage());
                    stopExitOrderChase(tradeId);
                }
            } catch (Exception e) {
                log.warn("⚠️ Exit chase iteration failed for trade {}: {}", tradeId, e.getMessage());
            }
        };

        ScheduledFuture<?> future = exitChaseScheduler.scheduleWithFixedDelay(task, 0L, delayMillis, TimeUnit.MILLISECONDS);
        exitChaseFutures.put(tradeId, future);
        log.info("🧵 Exit chase started for trade {} order {}", tradeId, exitOrderId);
    }

    public void stopExitOrderChase(Long tradeId) {
        if (tradeId == null) {
            return;
        }
        ScheduledFuture<?> future = exitChaseFutures.remove(tradeId);
        if (future != null) {
            future.cancel(true);
            log.info("🧵 Exit chase completed for trade {}", tradeId);
        }
        exitChaseStates.remove(tradeId);
    }

    private Double determineExitChasePrice(TriggeredTradeSetupEntity trade, ExitChaseState state) {
        if (trade == null) {
            return null;
        }

        Double stopLoss = trade.getStopLoss();
        if (!state.bufferedAttempted) {
            state.bufferedAttempted = true;
            if (stopLoss != null && stopLoss > 0) {
                Double candidate = sanitizeExitPriceCandidate(trade, stopLoss * 0.9975d, "EXIT_CHASE_BUFFER");
                if (candidate != null) {
                    return candidate;
                }
            }
        }

        Double bid = null;
        if (trade.getScripCode() != null) {
            Optional<QuoteCacheService.QuoteSnapshot> snapshot = quoteCacheService.getSnapshot(trade.getScripCode());
            if (snapshot.isPresent() && snapshot.get().getBestBid() != null && snapshot.get().getBestBid() > 0) {
                bid = snapshot.get().getBestBid();
            }
        }

        if (bid != null && bid > 0) {
            Double candidate = sanitizeExitPriceCandidate(trade, bid, "EXIT_CHASE_BID");
            if (candidate != null) {
                return candidate;
            }
        }

        Double ltp = ltpCacheService.getLtp(trade.getScripCode());
        if (ltp != null && ltp > 0) {
            Double candidate = sanitizeExitPriceCandidate(trade, ltp, "EXIT_CHASE_LTP");
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private double resolveEntryAttemptPrice(EntryDiagnostics diagnostics, int attemptIndex, double fallbackLtp) {
        Double bid = diagnostics.bestBid();
        Double ask = diagnostics.bestAsk();
        Double mid = diagnostics.recommendedLimit();

        double rawPrice;

        if (bid == null || ask == null || mid == null) {
            rawPrice = fallbackLtp;
        } else {
            double spread = Math.max(0d, ask - bid);
            rawPrice = switch (attemptIndex) {
                case 0 -> mid;
                case 1 -> Math.min(ask, mid + spread * SECOND_ATTEMPT_SPREAD_FRACTION);
                default -> ask;
            };
        }

        return normalisePriceToTick(diagnostics, rawPrice);
    }

    private double normalisePriceToTick(EntryDiagnostics diagnostics, double price) {
        double tickSize = resolveTickSizeFromDiagnostics(diagnostics);
        if (tickSize <= 0d) {
            return roundPrice(price);
        }

        double scaled = Math.round(price / tickSize) * tickSize;
        double rounded = Math.round(scaled * 100.0d) / 100.0d;
        double roundedTick = Math.round(tickSize * 100.0d) / 100.0d;
        double epsilon = 1e-6;
        double residual = rounded % roundedTick;
        if (residual > epsilon && roundedTick - residual > epsilon) {
            if (residual < roundedTick / 2) {
                rounded = Math.round((rounded - residual) * 100.0d) / 100.0d;
            } else {
                rounded = Math.round((rounded + (roundedTick - residual)) * 100.0d) / 100.0d;
            }
        }
        return rounded;
    }

    private double resolveTickSizeFromDiagnostics(EntryDiagnostics diagnostics) {
        return 0.05d;
    }

    public void squareOffTrade(Long id) {
        squareOffTrade(id, null);
    }

    private boolean hasOptionType(String optionType) {
        return optionType != null && !optionType.trim().isEmpty();
    }

    private int resolveQuickDefaultLots(Long appUserId) {
        int configuredLots = readIntConfig(appUserId, "quick_trade_default_lots");
        if (configuredLots > 0) {
            return configuredLots;
        }
        configuredLots = readIntConfig(appUserId, "option_stock_lot_size");
        if (configuredLots > 0) {
            return configuredLots;
        }
        return 1;
    }

    private int readIntConfig(Long appUserId, String key) {
        try {
            String raw = userConfigService.getConfig(appUserId, key, null);
            if (raw != null && !raw.isBlank()) {
                int value = Integer.parseInt(raw.trim());
                if (value > 0) {
                    return value;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private double resolvePercentageConfig(Long appUserId, String key, double defaultValue) {
        try {
            String raw = userConfigService.getConfig(appUserId, key, null);
            if (raw != null && !raw.isBlank()) {
                double value = Double.parseDouble(raw.trim());
                if (value >= 0) {
                    return value;
                }
            }
        } catch (Exception ignored) { }
        return defaultValue;
    }

    private double roundPrice(double price) {
        return Math.round(price * 100.0d) / 100.0d;
    }

    private Integer resolveSpotScripCodeForOption(String instrumentName) {
        if (instrumentName == null || instrumentName.isBlank()) {
            return null;
        }
        String trimmed = instrumentName.trim();

        for (String candidate : SpotSymbolAliases.candidates(trimmed)) {
            Integer spot = resolveSpotScripFromExchange(candidate, "NC");
            if (spot != null) {
                return spot;
            }
            spot = resolveSpotScripFromExchange(candidate, "BC");
            if (spot != null) {
                return spot;
            }
        }

        Integer futureSpot = resolveIndexFutureScripCode(trimmed);
        if (futureSpot != null) {
            return futureSpot;
        }

        log.warn("Could not resolve spot scrip code for instrument: {}", trimmed);
        return null;
    }

    private void subscribeToSpotFeed(ScriptMasterEntity spotScript) {
        if (spotScript == null || spotScript.getScripCode() == null) {
            return;
        }
        String spotKey = spotFeedKey(spotScript);
        if (isSharekhanIndexSpot(spotScript)) {
            webSocketSubscriptionService.subscribeToScripLtp(spotKey);
        } else {
            webSocketSubscriptionService.subscribeToScrip(spotKey);
        }
    }

    private String spotFeedKey(ScriptMasterEntity spotScript) {
        return spotScript.getExchange() + spotScript.getScripCode();
    }

    private boolean isSharekhanIndexSpot(ScriptMasterEntity spotScript) {
        Integer scripCode = spotScript.getScripCode();
        return "NC".equalsIgnoreCase(spotScript.getExchange())
                && (Integer.valueOf(20000).equals(scripCode) || Integer.valueOf(26009).equals(scripCode));
    }

    private Integer resolveSpotScripFromExchange(String instrumentName, String exchange) {
        try {
            Optional<ScriptMasterEntity> spotOpt =
                    scriptMasterRepository.findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull(exchange, instrumentName);
            return spotOpt.map(ScriptMasterEntity::getScripCode).orElse(null);
        } catch (Exception e) {
            log.warn("Error resolving spot scrip code for instrument {} on {}: {}", instrumentName, exchange, e.getMessage());
            return null;
        }
    }

    private Integer resolveIndexFutureScripCode(String instrumentName) {
        String normalized = instrumentName.trim().toUpperCase(Locale.ROOT);
        String exchange = INDEX_FUTURE_EXCHANGES.get(normalized);
        if (exchange == null) {
            return null;
        }

        List<ScriptMasterEntity> futures =
                scriptMasterRepository.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange, normalized);
        if (futures == null || futures.isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now(MARKET_ZONE);
        ScriptMasterEntity best = null;
        LocalDate bestExpiry = null;

        for (ScriptMasterEntity future : futures) {
            if (future == null ||
                    future.getOptionType() == null ||
                    !future.getOptionType().equalsIgnoreCase("FUT")) {
                continue;
            }
            String instType = future.getInstrumentType();
            if (instType == null ||
                    !(instType.equalsIgnoreCase("FI") || instType.equalsIgnoreCase("FS"))) {
                continue;
            }

            LocalDate expiryDate = parseFutureExpiry(future.getExpiry());
            if (expiryDate == null) {
                continue;
            }

            if (best == null) {
                best = future;
                bestExpiry = expiryDate;
                continue;
            }

            boolean bestExpired = bestExpiry.isBefore(today);
            boolean currentExpired = expiryDate.isBefore(today);

            if (bestExpired && !currentExpired) {
                best = future;
                bestExpiry = expiryDate;
                continue;
            }

            if (bestExpired == currentExpired && expiryDate.isBefore(bestExpiry)) {
                best = future;
                bestExpiry = expiryDate;
            }
        }

        return best != null ? best.getScripCode() : null;
    }

    private LocalDate parseFutureExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            return null;
        }
        String trimmed = expiry.trim();
        for (DateTimeFormatter formatter : FUTURE_EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        if (trimmed.length() >= 10) {
            try {
                return LocalDate.parse(trimmed.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
            }
        }
        log.debug("Unable to parse future expiry '{}' for instrument", expiry);
        return null;
    }

    private OrderPlacementResult placeExitOrderAndPersist(TriggeredTradeSetupEntity persisted,
                                                          double exitPrice,
                                                          String exitReason,
                                                          TriggeredTradeStatus placedStatus) {
        String lockKey = buildExitLockKey(persisted);
        try {
            return orderPlacementGuard.withLock(lockKey, ORDER_LOCK_TIMEOUT,
                    () -> placeExitOrderAndPersistWithoutLock(persisted, exitPrice, exitReason, placedStatus));
        } catch (OrderPlacementGuard.LockAcquisitionException e) {
            log.warn("⚠️ Duplicate exit placement prevented for trade {} (exitOrderId={}): {}",
                    persisted != null ? persisted.getId() : null,
                    persisted != null ? persisted.getExitOrderId() : "N/A",
                    e.getMessage());
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .rejectionReason("Another exit placement is in progress")
                    .build();
        }
    }

    private OrderPlacementResult placeExitOrderAndPersistWithoutLock(TriggeredTradeSetupEntity persisted,
                                                                     double exitPrice,
                                                                     String exitReason,
                                                                     TriggeredTradeStatus placedStatus) {
        BrokerContext exitCtx = resolveBrokerContext(persisted.getBrokerCredentialsId(), persisted.getAppUserId());
        if (exitCtx == null || exitCtx.getBrokerName() == null) {
            throw new IllegalStateException("No active broker configured for this user (exit)");
        }

        BrokerService brokerService = brokerServiceFactory.getService(exitCtx.getBrokerName());
        if (brokerService == null) {
            throw new IllegalStateException("No broker service found for: " + exitCtx.getBrokerName());
        }

        Double safeExitPrice = sanitizeExitPriceCandidate(persisted, exitPrice, "EXIT_PLACE");
        if (safeExitPrice == null) {
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .rejectionReason("Implausible exit price for instrument")
                    .attemptedPrice(exitPrice)
                    .build();
        }

        TradeEventLogger.logOrderAttempt("EXIT", persisted, 1, "PLACE", safeExitPrice, persisted.getExitOrderId());
        OrderPlacementResult result = brokerService.placeExitOrder(persisted, exitCtx, safeExitPrice);
        if (result == null) {
            result = OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .rejectionReason("Broker returned no exit placement result")
                    .attemptedPrice(safeExitPrice)
                    .build();
        } else if (result.getAttemptedPrice() == null) {
            result.setAttemptedPrice(safeExitPrice);
        }
        if (!result.isSuccess()) {
            TradeEventLogger.logOrderRejected("EXIT", persisted, result.getRejectionReason(), safeExitPrice, result.getAttemptedPrice());
            return result;
        }

        TradeEventLogger.logOrderAccepted("EXIT", persisted, result, safeExitPrice);

        // Persist exitOrderId using a native update first to avoid concurrency issues
        java.time.LocalDateTime placedAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        triggeredTradeRepo.setExitOrderId(persisted.getId(), result.getOrderId(), placedAt);

        // Evict caches and clear persistence context so subsequent reads see the native update
        try {
            if (entityManager != null && entityManager.getEntityManagerFactory() != null) {
                entityManager.getEntityManagerFactory().getCache().evict(TriggeredTradeSetupEntity.class, persisted.getId());
                entityManager.clear();
            }
        } catch (Exception ex) {
            log.debug("Failed to evict/clear JPA caches for trade {}: {}", persisted.getId(), ex.getMessage());
        }

        try {
            persisted.setExitOrderId(result.getOrderId());
            persisted.setStatus(placedStatus);
            persisted.setExitReason(exitReason);
            persisted.setExitOrderPlacedAt(placedAt);
            triggeredTradeRepo.save(persisted);
        } catch (Exception e) {
            log.debug("Failed to persist exitOrderId/status {} for trade {}: {}", placedStatus, persisted.getId(), e.getMessage());
        }

        if (!"Fully Executed".equalsIgnoreCase(result.getStatus())
                && TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(placedStatus)) {
            scheduleExitOrderChase(persisted);
        }

        // If the broker response indicates the exit order was fully executed, mark trade exited immediately
        if ("Fully Executed".equalsIgnoreCase(result.getStatus()) && result.getExecutedPrice() != null) {
            try {
                double exitPriceVal = result.getExecutedPrice();
                Double pnlVal = result.getPnl();

                Double entryPriceForPnl = resolveEntryPriceForPnl(persisted);
                if (pnlVal == null && entryPriceForPnl != null && persisted.getQuantity() != null) {
                    pnlVal = java.math.BigDecimal.valueOf(exitPriceVal)
                            .subtract(java.math.BigDecimal.valueOf(entryPriceForPnl))
                            .multiply(java.math.BigDecimal.valueOf(persisted.getQuantity()))
                            .setScale(2, java.math.RoundingMode.HALF_UP)
                            .doubleValue();
                }

                int updated = triggeredTradeRepo.markExitedWithPNL(persisted.getId(),
                        TriggeredTradeStatus.EXITED_SUCCESS,
                        exitPriceVal,
                        java.time.LocalDateTime.now(),
                        pnlVal);
                if (updated == 1) {
                    TradeEventLogger.logOrderExecuted("EXIT", persisted, exitPriceVal, result.getStatus());
                    try {
                        if (entityManager != null && entityManager.getEntityManagerFactory() != null) {
                            entityManager.getEntityManagerFactory().getCache().evict(TriggeredTradeSetupEntity.class, persisted.getId());
                            entityManager.clear();
                        }
                    } catch (Exception ignore) {
                        log.debug("Failed to evict/clear JPA cache after markExited for {}: {}", persisted.getId(), ignore.getMessage());
                    }
                    try {
                        webSocketSubscriptionService.unsubscribeFromScrip(persisted.getExchange() + persisted.getScripCode());
                    } catch (Exception ignored) {
                        log.debug("Failed to unsubscribe from scrip after immediate exit for {}: {}", persisted.getId(), ignored.getMessage());
                    }
                    return result;
                }
            } catch (Exception ex) {
                log.debug("Failed to process immediate exit response for trade {}: {}", persisted.getId(), ex.getMessage());
            }
        }

        // Otherwise start order status polling so we know when this exit order completes
        try {
            final Long persistedIdForPoll = persisted.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            TriggeredTradeSetupEntity toMonitor = triggeredTradeRepo.findById(persistedIdForPoll).orElse(null);
                            if (toMonitor != null) {
                                eventPublisher.publishEvent(new OrderPlacedEvent(toMonitor));
                            } else {
                                log.warn("AfterCommit: trade {} not found to start polling", persistedIdForPoll);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to start order status polling (afterCommit) for trade {}: {}", persistedIdForPoll, e.getMessage());
                        }
                    }
                });
            } else {
                eventPublisher.publishEvent(new OrderPlacedEvent(persisted));
            }
        } catch (Exception e) {
            log.warn("Failed to schedule order status polling for trade {}: {}", persisted.getId(), e.getMessage());
        }

        return result;
    }

    public boolean hasUsableTradedEntryPrice(TriggerTradeRequestEntity request, double tradedLtp) {
        if (request == null) {
            return true;
        }
        boolean usesSpotReference = Boolean.TRUE.equals(request.getUseSpotForEntry())
                || Boolean.TRUE.equals(request.getUseSpotForSl())
                || Boolean.TRUE.equals(request.getUseSpotForTarget())
                || Boolean.TRUE.equals(request.getUseSpotPrice());
        return !looksLikeSpotPriceForSpotBasedOptionTrade(request.getEntryPrice(), tradedLtp, usesSpotReference);
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

    public boolean hasUsableTradedExitPrice(TriggeredTradeSetupEntity trade, double exitPrice) {
        if (trade == null) {
            return true;
        }

        if (isImplausibleOptionPriceCandidate(trade, exitPrice)) {
            return false;
        }

        if (trade.getEntryPrice() == null) {
            return true;
        }

        if (!usesSpotReference(trade)) {
            return true;
        }

        boolean exitLooksSpot = looksLikeSpotPriceForSpotBasedOptionTrade(trade.getEntryPrice(), exitPrice, true);
        boolean entryLooksSpotWhileExitLooksOption = trade.getActualEntryPrice() != null
                && looksLikeSpotPriceForSpotBasedOptionTrade(trade.getEntryPrice(), trade.getActualEntryPrice(), true)
                && !exitLooksSpot;
        return !exitLooksSpot && !entryLooksSpotWhileExitLooksOption;
    }

    private Double sanitizeExitPriceCandidate(TriggeredTradeSetupEntity trade, Double candidatePrice, String reason) {
        if (candidatePrice == null || !Double.isFinite(candidatePrice) || candidatePrice <= 0d) {
            return null;
        }
        double rounded = roundPrice(candidatePrice);
        if (isImplausibleOptionPriceCandidate(trade, rounded)) {
            log.warn("Ignoring implausible option exit price {} for trade {} (reason={}) entry={} actualEntry={}",
                    rounded,
                    trade != null ? trade.getId() : null,
                    reason,
                    trade != null ? formatPrice(trade.getEntryPrice()) : "NA",
                    trade != null ? formatPrice(trade.getActualEntryPrice()) : "NA");
            return null;
        }
        return rounded;
    }

    private boolean isImplausibleOptionPriceCandidate(TriggeredTradeSetupEntity trade, double candidatePrice) {
        if (trade == null || !hasOptionType(trade.getOptionType()) || candidatePrice <= 0d) {
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

    private boolean looksLikeSpotPriceForSpotBasedOptionTrade(Double spotReferencePrice,
                                                              double tradedPrice,
                                                              boolean usesSpotReference) {
        if (!usesSpotReference || spotReferencePrice == null) {
            return false;
        }

        double spotPrice = spotReferencePrice;
        if (spotPrice <= 0 || tradedPrice <= 0) {
            return false;
        }

        return tradedPrice > 100d && Math.abs(tradedPrice - spotPrice) / spotPrice <= 0.20d;
    }

    private boolean usesSpotReference(TriggeredTradeSetupEntity trade) {
        return Boolean.TRUE.equals(trade.getUseSpotForEntry())
                || Boolean.TRUE.equals(trade.getUseSpotForSl())
                || Boolean.TRUE.equals(trade.getUseSpotForTarget())
                || Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    public void handleEntryOrderExecution(TriggeredTradeSetupEntity trade) {
        try {
            maybePlaceTargetOrder(trade);
        } catch (Exception e) {
            log.warn("Failed to place target order for trade {}: {}", trade != null ? trade.getId() : null, e.getMessage());
        }
    }

    private void maybePlaceTargetOrder(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getId() == null) {
            return;
        }

        // Skip when TSL logic is enabled or when target is based on spot price
        if (Boolean.TRUE.equals(trade.getTslEnabled()) || isSpotTarget(trade)) {
            return;
        }

        if (trade.getQuantity() == null || trade.getQuantity() <= 0) {
            return;
        }

        if (trade.getExitOrderId() != null) {
            return;
        }

        if (isSimulatorBroker(trade)) {
            log.debug("Skipping auto target order for simulator trade {}", trade.getId());
            return;
        }

        Double targetPrice = pickTargetPrice(trade);
        if (targetPrice == null || targetPrice <= 0) {
            return;
        }

        TriggeredTradeSetupEntity persisted = triggeredTradeRepo.findById(trade.getId()).orElse(null);
        if (persisted == null) {
            return;
        }

        if (persisted.getExitOrderId() != null) {
            log.debug("Exit order already exists for trade {}. Skipping auto target order.", persisted.getId());
            return;
        }

        // Ensure the status is still EXECUTED before attempting target placement
        if (!TriggeredTradeStatus.EXECUTED.equals(persisted.getStatus())) {
            return;
        }

        ExitDiagnostics exitDiagnostics = analyseExit(persisted, targetPrice, "TARGET_ORDER_AUTO");
        logExitDiagnostics(persisted, exitDiagnostics, targetPrice);

        Double resolvedTargetPrice = exitDiagnostics.recommendedLimit() != null
                ? exitDiagnostics.recommendedLimit()
                : targetPrice;

        OrderPlacementResult result = placeExitOrderAndPersist(persisted, resolvedTargetPrice, "TARGET_ORDER_AUTO", TriggeredTradeStatus.TARGET_ORDER_PLACED);

        if (result == null || !result.isSuccess()) {
            log.warn("Failed to auto-place target order for trade {}: {}", persisted.getId(),
                    result != null ? result.getRejectionReason() : "Unknown error");
            // Keep trade in EXECUTED state so monitoring logic can continue to manage exits
            try {
                persisted.setStatus(TriggeredTradeStatus.EXECUTED);
                persisted.setExitOrderId(null);
                triggeredTradeRepo.save(persisted);
            } catch (Exception e) {
                log.debug("Failed to reset trade {} status after target order failure: {}", persisted.getId(), e.getMessage());
            }
        } else {
            log.info("✅ Auto target order placed for trade {} at price {}", persisted.getId(), targetPrice);
        }
    }

    private Double pickTargetPrice(TriggeredTradeSetupEntity trade) {
        if (trade == null) return null;
        if (trade.getTarget1() != null && trade.getTarget1() > 0) return trade.getTarget1();
        if (trade.getTarget2() != null && trade.getTarget2() > 0) return trade.getTarget2();
        if (trade.getTarget3() != null && trade.getTarget3() > 0) return trade.getTarget3();
        return null;
    }

    public boolean rescheduleTargetOrderAfterHours(Long tradeId) {
        if (tradeId == null) return false;

        TriggeredTradeSetupEntity persisted = triggeredTradeRepo.findById(tradeId).orElse(null);
        if (persisted == null) {
            log.debug("Skipping after-hours reschedule — trade {} not found", tradeId);
            return false;
        }

        if (!TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(persisted.getStatus())) {
            log.debug("Skipping after-hours reschedule — trade {} in status {}", tradeId, persisted.getStatus());
            return false;
        }

        if (Boolean.TRUE.equals(persisted.getIntraday())) {
            log.debug("Skipping after-hours reschedule — trade {} is intraday", tradeId);
            return false;
        }

        BrokerContext ctx = resolveBrokerContext(persisted.getBrokerCredentialsId(), persisted.getAppUserId());
        if (ctx == null || ctx.getBrokerName() == null) {
            log.warn("Unable to resolve broker context for trade {} while scheduling after-hours target", tradeId);
            return false;
        }

        Broker broker;
        try {
            broker = Broker.fromDisplayName(ctx.getBrokerName());
        } catch (Exception ex) {
            log.debug("Skipping after-hours reschedule — unsupported broker {} for trade {}", ctx.getBrokerName(), tradeId);
            return false;
        }

        if (broker != Broker.SHAREKHAN) {
            log.debug("Skipping after-hours reschedule — trade {} uses broker {}", tradeId, broker.getDisplayName());
            return false;
        }

        ZoneId zone = ZoneId.of("Asia/Kolkata");
        LocalDateTime lastPlaced = persisted.getExitOrderPlacedAt();
        if (lastPlaced != null) {
            LocalDate today = LocalDate.now(zone);
            if (today.equals(lastPlaced.toLocalDate())) {
                LocalTime afterHoursWindowStart = LocalTime.of(17, 0);
                if (!lastPlaced.toLocalTime().isBefore(afterHoursWindowStart)) {
                    log.debug("Skipping after-hours reschedule — trade {} exit order already refreshed after {}", tradeId, afterHoursWindowStart);
                    return false;
                }
                if (!lastPlaced.toLocalTime().isBefore(LocalTime.of(16, 0))) {
                    log.info("Reissuing after-hours target for trade {} (existing exit from {} placed post market close)", tradeId, lastPlaced);
                }
            }
        }

        Double targetPrice = pickTargetPrice(persisted);
        if (targetPrice == null || targetPrice <= 0) {
            log.warn("Unable to determine target price for after-hours reschedule on trade {}", tradeId);
            return false;
        }

        String previousOrderId = persisted.getExitOrderId();
        ExitDiagnostics exitDiagnostics = analyseExit(persisted, targetPrice, "TARGET_ORDER_AFTER_HOURS");
        logExitDiagnostics(persisted, exitDiagnostics, targetPrice);

        Double resolvedAfterHoursTargetPrice = exitDiagnostics.recommendedLimit() != null
                ? exitDiagnostics.recommendedLimit()
                : targetPrice;

        OrderPlacementResult result = placeExitOrderAndPersist(persisted, resolvedAfterHoursTargetPrice, "TARGET_ORDER_AFTER_HOURS", TriggeredTradeStatus.TARGET_ORDER_PLACED);
        if (result == null || !result.isSuccess()) {
            log.warn("Failed to place after-hours target exit for trade {}: {}", tradeId,
                    result != null ? result.getRejectionReason() : "No response");
            return false;
        }

        log.info("🌙 After-hours target exit order placed for trade {} (oldOrderId={}, newOrderId={})",
                tradeId, previousOrderId, result.getOrderId());
        return true;
    }

    private boolean isSpotTarget(TriggeredTradeSetupEntity trade) {
        if (trade == null) return false;
        if (Boolean.TRUE.equals(trade.getUseSpotForTarget())) {
            return true;
        }
        return trade.getUseSpotForTarget() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private boolean isSpotStopLoss(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForSl())) {
            return true;
        }
        return trade.getUseSpotForSl() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private boolean isSimulatorBroker(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getBrokerCredentialsId() == null) {
            return false;
        }
        return brokerCredentialsRepository.findById(trade.getBrokerCredentialsId())
                .map(creds -> creds.getBrokerName() != null &&
                        creds.getBrokerName().equalsIgnoreCase(Broker.SIMULATOR.getDisplayName()))
                .orElse(false);
    }

    private ModifyExitOrderResult modifyExistingExitOrder(TriggeredTradeSetupEntity trade,
                                                          double newPrice,
                                                          String exitReason,
                                                          TriggeredTradeStatus postStatus) {
        if (trade == null || trade.getExitOrderId() == null || trade.getExitOrderId().isBlank()) {
            String msg = String.format("Modify exit order skipped - no exitOrderId for trade %s", trade != null ? trade.getId() : null);
            log.warn(msg);
            return new ModifyExitOrderResult(false, "No exit order is currently active for this trade.");
        }

        Double safeNewPrice = sanitizeExitPriceCandidate(trade, newPrice, "EXIT_MODIFY_" + (exitReason != null ? exitReason : "UNKNOWN"));
        if (safeNewPrice == null) {
            String msg = String.format("Modify exit order skipped for trade %s due to implausible price %.2f", trade.getId(), newPrice);
            log.warn(msg);
            return new ModifyExitOrderResult(false, "Exit modify rejected: implausible price for this instrument.");
        }

        BrokerContext ctx = resolveBrokerContext(trade.getBrokerCredentialsId(), trade.getAppUserId());
        if (ctx == null || ctx.getBrokerName() == null) {
            String msg = String.format("Unable to resolve broker context for modifying exit order of trade %s", trade.getId());
            log.warn(msg);
            return new ModifyExitOrderResult(false, "Broker credentials not available for this trade.");
        }

        Broker broker;
        try {
            broker = Broker.fromDisplayName(ctx.getBrokerName());
        } catch (Exception e) {
            String msg = String.format("Unsupported broker %s for exit order modify on trade %s", ctx.getBrokerName(), trade.getId());
            log.warn(msg);
            return new ModifyExitOrderResult(false, "Broker " + ctx.getBrokerName() + " is not supported for exit order modification.");
        }

        if (broker == Broker.SIMULATOR) {
            TradeEventLogger.logOrderAttempt("EXIT", trade, 1, "MODIFY", safeNewPrice, trade.getExitOrderId());
            trade.setExitReason(exitReason);
            if (postStatus != null) {
                trade.setStatus(postStatus);
            }
            try {
                triggeredTradeRepo.save(trade);
            } catch (Exception e) {
                String msg = String.format("Failed to persist simulator exit modification for trade %s: %s", trade.getId(), e.getMessage());
                log.warn(msg);
                return new ModifyExitOrderResult(false, "Simulator exit modification failed to persist.");
            }
            log.info("✏️ Simulator exit order {} updated locally for trade {} to price {}", trade.getExitOrderId(), trade.getId(), safeNewPrice);
            return new ModifyExitOrderResult(true, "Simulator exit order price updated.");
        }

        if (broker != Broker.SHAREKHAN) {
            BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
            if (brokerService instanceof ModifiableEntryBrokerService modifiableBroker) {
                TradeEventLogger.logOrderAttempt("EXIT", trade, 1, "MODIFY", safeNewPrice, trade.getExitOrderId());
                OrderPlacementResult result = modifiableBroker.modifyEntryOrder(trade, ctx, trade.getExitOrderId(), safeNewPrice);
                if (result != null && result.isSuccess()) {
                    trade.setExitReason(exitReason);
                    if (postStatus != null) {
                        trade.setStatus(postStatus);
                    }
                    triggeredTradeRepo.save(trade);
                    log.info("✏️ {} exit order {} modify submitted for trade {} to price {}",
                            broker.getDisplayName(), trade.getExitOrderId(), trade.getId(), safeNewPrice);
                    return new ModifyExitOrderResult(true, broker.getDisplayName() + " modify submitted successfully.");
                }

                String reason = result != null && result.getRejectionReason() != null
                        ? result.getRejectionReason()
                        : "Broker returned no modify result";
                log.warn("{} modify rejected for trade {}: {}", broker.getDisplayName(), trade.getId(), reason);
                return new ModifyExitOrderResult(false, broker.getDisplayName() + " modify rejected: " + reason);
            }

            log.warn("Modify exit order currently unsupported for broker {}. Trade {}", broker.getDisplayName(), trade.getId());
            return new ModifyExitOrderResult(false, "Exit order modification is not yet supported for broker " + broker.getDisplayName() + ".");
        }

        try {
            String accessToken = tokenStoreService.getAccessToken(broker, ctx.getCustomerId());
            if (accessToken == null) {
                accessToken = tokenStoreService.getAccessToken(broker);
            }

            if (accessToken == null || ctx.getApiKey() == null) {
                String msg = String.format("Missing access token/api key for modifying exit order of trade %s", trade.getId());
                log.warn(msg);
                return new ModifyExitOrderResult(false, "Sharekhan token unavailable. Please re-authenticate and retry.");
            }

            TradeEventLogger.logOrderAttempt("EXIT", trade, 1, "MODIFY", safeNewPrice, trade.getExitOrderId());
            com.sharekhan.SharekhanConnect sharekhanConnect = new com.sharekhan.SharekhanConnect(null, ctx.getApiKey(), accessToken);
            JSONObject response = ShareKhanOrderUtil.modifyOrder(sharekhanConnect, trade, safeNewPrice, ctx.getCustomerId(), ctx.getClientCode());

            if (response == null) {
                String msg = String.format("Modify order returned null for trade %s", trade.getId());
                log.warn(msg);
                return new ModifyExitOrderResult(false, "Sharekhan returned an empty response while modifying the order.");
            }

            trade.setExitReason(exitReason);
            if (postStatus != null) {
                trade.setStatus(postStatus);
            }
            triggeredTradeRepo.save(trade);
            log.info("✏️ Modified exit order {} for trade {} to price {}", trade.getExitOrderId(), trade.getId(), safeNewPrice);
            return new ModifyExitOrderResult(true, "Sharekhan modify submitted successfully.");
        } catch (SharekhanAPIException e) {
            log.warn("Sharekhan modify rejected for trade {}: {}", trade.getId(), e.getMessage());
            return new ModifyExitOrderResult(false, "Sharekhan rejected modify request: " + e.getMessage());
        } catch (IOException e) {
            log.warn("I/O error while modifying exit order for trade {}: {}", trade.getId(), e.getMessage());
            return new ModifyExitOrderResult(false, "Network issue while talking to Sharekhan: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to modify exit order for trade {}: {}", trade.getId(), e.getMessage(), e);
            return new ModifyExitOrderResult(false, "Sharekhan modify request failed: " + e.getMessage());
        }
    }

    public ModifyExitOrderResult modifyExitOrderPrice(Long tradeId, double newPrice, String reason) {
        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findById(tradeId)
                .orElseThrow(() -> new RuntimeException("Trade not found: " + tradeId));
        return modifyExistingExitOrder(trade, newPrice, reason, trade.getStatus());
    }

    public boolean modifyExitOrderForStop(TriggeredTradeSetupEntity trade, double newPrice) {
        if (trade == null) {
            return false;
        }
        ModifyExitOrderResult result = modifyExistingExitOrder(trade, newPrice, "STOP_LOSS_HIT", TriggeredTradeStatus.EXIT_ORDER_PLACED);
        if (!result.isSuccess()) {
            log.warn("Stop-loss modify failed for trade {}: {}", trade.getId(), result.getMessage());
        } else {
            scheduleExitOrderChase(trade);
        }
        return result.isSuccess();
    }

    public boolean modifyExitOrderForTarget(TriggeredTradeSetupEntity trade, double newPrice) {
        if (trade == null) {
            return false;
        }
        ModifyExitOrderResult result = modifyExistingExitOrder(trade, newPrice, "TARGET_HIT", TriggeredTradeStatus.EXIT_ORDER_PLACED);
        if (!result.isSuccess()) {
            log.warn("Target modify failed for trade {}: {}", trade.getId(), result.getMessage());
        } else {
            scheduleExitOrderChase(trade);
        }
        return result.isSuccess();
    }

    public boolean modifyExitOrderForIntradayClose(TriggeredTradeSetupEntity trade, double newPrice) {
        if (trade == null) {
            return false;
        }
        ModifyExitOrderResult result = modifyExistingExitOrder(trade, newPrice, "INTRADAY_CLOSE", TriggeredTradeStatus.TARGET_ORDER_PLACED);
        if (!result.isSuccess()) {
            log.warn("Intraday close modify failed for trade {}: {}", trade.getId(), result.getMessage());
        }
        return result.isSuccess();
    }

    public void squareOffTrade(Long id, Double price) {
        squareOffTrade(id, price, TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }

    public void squareOffTrade(Long id, Double price, TriggeredTradeStatus placedStatus) {
        TriggeredTradeSetupEntity tradeSetupEntity = triggeredTradeRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Trade not found"));

        double exitPrice;
        if (price != null) {
            exitPrice = price;
        } else {
            exitPrice = ltpCacheService.getLtp(tradeSetupEntity.getScripCode());
        }
        squareOff(tradeSetupEntity, exitPrice, "Manual Exit", placedStatus);
    }


    public enum TradeStatus {
        FULLY_EXECUTED,
        REJECTED,
        PENDING,
        NO_RECORDS
    }

    public TradeStatus evaluateOrderFinalStatus(TriggeredTradeSetupEntity tradeSetupEntity, JSONObject orderHistoryResponse) {
        if (orderHistoryResponse == null) {
            return TradeStatus.NO_RECORDS;
        }
        Object data = orderHistoryResponse.opt("data");

        if (data instanceof String && "no_records".equalsIgnoreCase((String) data)) {
            return TradeStatus.NO_RECORDS;
        }

        JSONArray trades = orderHistoryResponse.optJSONArray("data");
        if (trades == null) {
            return TradeStatus.NO_RECORDS;
        }

        Set<String> orderStatusSet = new HashSet<>();
        for (int i = 0; i < trades.length(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            String statusRaw = firstNonBlankJsonString(trade, "orderStatus", "status");
            String status = statusRaw;
            String normalized = statusRaw.toLowerCase(Locale.ROOT);

            // Normalize known statuses
            if (isPartiallyExecutedBrokerStatus(statusRaw, trade)) {
                status = "Pending"; // wait until all entry quantity is filled before marking EXECUTED
            } else if (isFullyExecutedBrokerStatus(statusRaw, trade)) {
                status = "Fully Executed";
            } else if (normalized.contains("reject") || normalized.contains("cancel")) {
                status = "Rejected";
            } else if (normalized.contains("pending") || normalized.contains("process") || normalized.contains("trigger")) {
                status = "Pending";
            }else{
                status = "Pending"; // treat unknown as pending for safety
            }

            // If fully executed, use avgPrice if available else try orderPrice
            if ("Fully Executed".equals(status)) {
                Double price = firstDoubleJsonValue(trade,
                        "avgPrice", "average_price", "execPrice", "orderPrice", "price");

                if (price != null) {
                    if (TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(tradeSetupEntity.getStatus())) {

                        Double originalTriggerPrice = tradeSetupEntity.getEntryPrice();
                        tradeSetupEntity.setActualEntryPrice(price);
                        tradeSetupEntity.setEntryAt(LocalDateTime.now());

                        // Robust check for spot entry
                        boolean isSpotEntry = Boolean.TRUE.equals(tradeSetupEntity.getUseSpotForEntry()) 
                                || (tradeSetupEntity.getUseSpotForEntry() == null && Boolean.TRUE.equals(tradeSetupEntity.getUseSpotPrice()));
                        boolean isSharekhanSource = isSharekhanSource(tradeSetupEntity.getSource());

                        // For non-spot trades, update entryPrice to executed price for consistency
                        // and to allow existing SL/TGT adjustment logic to work as-is.
                        // For spot trades, entryPrice remains the spot trigger price.
                        // For Sharekhan source signals, entry/SL/target levels are preserved from the source.
                        if (!isSpotEntry && !isSharekhanSource) {
                            tradeSetupEntity.setEntryPrice(price);
                        }

                        // New logic: Adjust SL and Targets based on actual entry price difference (slippage)
                        // This should only apply to non-spot trades where the trigger price was for the option itself.
                        if (!isSpotEntry && !isSharekhanSource) {
                            if (originalTriggerPrice != null && price != null) {
                                double diff = price - originalTriggerPrice;
                                if (Math.abs(diff) > 0.0001) { // if there is a significant difference
                                    log.info("Adjusting SL and Targets for trade {} due to entry price difference: {}. UseSpotForEntry={}, OriginalEntry={}, Executed={}",
                                            tradeSetupEntity.getId(), diff, isSpotEntry, originalTriggerPrice, price);
                                    
                                    if (tradeSetupEntity.getStopLoss() != null) {
                                        tradeSetupEntity.setStopLoss(tradeSetupEntity.getStopLoss() + diff);
                                    }
                                    if (tradeSetupEntity.getTarget1() != null) {
                                        tradeSetupEntity.setTarget1(tradeSetupEntity.getTarget1() + diff);
                                    }
                                    if (tradeSetupEntity.getTarget2() != null) {
                                        tradeSetupEntity.setTarget2(tradeSetupEntity.getTarget2() + diff);
                                    }
                                    if (tradeSetupEntity.getTarget3() != null) {
                                        tradeSetupEntity.setTarget3(tradeSetupEntity.getTarget3() + diff);
                                    }
                                 }
                            }
                        } else if (isSharekhanSource) {
                            log.info("Preserving Sharekhan source entry/SL/targets for trade {} after execution confirmation. OriginalEntry={}, Executed={}",
                                    tradeSetupEntity.getId(), originalTriggerPrice, price);
                        }

                    } else if (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus())
                            || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(tradeSetupEntity.getStatus())) {
                        tradeSetupEntity.setExitPrice(price);
                        tradeSetupEntity.setExitedAt(LocalDateTime.now());

                        Double entryForPnl = resolveEntryPriceForPnl(tradeSetupEntity);
                        if (entryForPnl != null && tradeSetupEntity.getQuantity() != null) {
                            double pnl = (price - entryForPnl)
                                    * tradeSetupEntity.getQuantity();
                            // just keep integer part, positive/negative safe
                            pnl = pnl > 0 ? Math.floor(pnl) : Math.ceil(pnl);
                            tradeSetupEntity.setPnl(pnl);
                        }
                    }
                }
            }
            orderStatusSet.add(status);
        }

        if (orderStatusSet.contains("Fully Executed")) return TradeStatus.FULLY_EXECUTED;
        if (orderStatusSet.contains("Rejected")) return TradeStatus.REJECTED;
        if (orderStatusSet.contains("Pending")) return TradeStatus.PENDING;

        // Still in progress or unknown status
        log.info("⏳ Order status set did not contain final state (seen={}): treating as NO_RECORDS/IN_PROGRESS", orderStatusSet);
        return TradeStatus.NO_RECORDS;
    }

    private boolean isPartiallyExecutedBrokerStatus(String statusRaw, JSONObject orderRow) {
        if (ShareKhanOrderUtil.isPartiallyExecutedStatus(statusRaw)) {
            return true;
        }
        String normalized = normalizeBrokerStatus(statusRaw);
        if (normalized.contains("partly") && normalized.contains("executed")) {
            return true;
        }
        Long filled = firstLongJsonValue(orderRow, "filled_quantity", "execQty");
        Long pending = firstLongJsonValue(orderRow, "pending_quantity", "openQty");
        return filled != null && pending != null && filled > 0L && pending > 0L;
    }

    private boolean isFullyExecutedBrokerStatus(String statusRaw, JSONObject orderRow) {
        if (ShareKhanOrderUtil.isFullyExecutedStatus(statusRaw)) {
            return true;
        }
        String normalized = normalizeBrokerStatus(statusRaw);
        if ("complete".equals(normalized)
                || "completed".equals(normalized)
                || normalized.contains("complete")
                || normalized.contains("fully filled")) {
            return true;
        }

        Long filled = firstLongJsonValue(orderRow, "filled_quantity", "execQty");
        Long quantity = firstLongJsonValue(orderRow, "quantity", "orderQty");
        Long pending = firstLongJsonValue(orderRow, "pending_quantity", "openQty");
        return filled != null
                && quantity != null
                && pending != null
                && quantity > 0L
                && filled >= quantity
                && pending == 0L;
    }

    private String firstNonBlankJsonString(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || !object.has(key) || object.isNull(key)) {
                continue;
            }
            String value = object.optString(key, "").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Double firstDoubleJsonValue(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !object.has(key) || object.isNull(key)) {
                continue;
            }
            Object value = object.opt(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            String text = object.optString(key, "").trim();
            if (!text.isBlank()) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private Long firstLongJsonValue(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !object.has(key) || object.isNull(key)) {
                continue;
            }
            Object value = object.opt(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            String text = object.optString(key, "").trim();
            if (!text.isBlank()) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private String normalizeBrokerStatus(String statusRaw) {
        if (statusRaw == null) {
            return "";
        }
        return statusRaw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutions() {
        return triggeredTradeRepo.findTop10ByOrderByIdDesc();
    }

    // New helper to return pending trigger requests for UI/SPA
    public List<org.com.sharekhan.entity.TriggerTradeRequestEntity> getPendingRequests() {
        return triggerTradeRequestRepository.findByStatus(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutionsForUser(Long userId) {
        return getRecentExecutionsForUser(userId, "own");
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutionsForUser(Long userId, String scope) {
        if (isSimulatorDashboardScope(scope)) {
            return triggeredTradeRepo.findTop10BySimulatorOrderByIdDesc(Broker.SIMULATOR.getDisplayName());
        }
        if (userId == null) return getRecentExecutions();
        if (isUserDashboardScope(scope)) {
            return triggeredTradeRepo.findTop10ByAppUserIdOrderByIdDesc(userId);
        }
        if (isAllDashboardScope(scope)) {
            return triggeredTradeRepo.findTop10ByAppUserIdOrSimulatorOrderByIdDesc(userId, Broker.SIMULATOR.getDisplayName());
        }
        return triggeredTradeRepo.findTop10ByAppUserIdExcludingSimulatorOrderByIdDesc(userId, Broker.SIMULATOR.getDisplayName());
    }

    public Page<TriggeredTradeSetupEntity> getRecentExecutionsForUser(Long userId, List<String> statusList, Pageable pageable) {
        return getRecentExecutionsForUser(userId, statusList, pageable, "own");
    }

    public Page<TriggeredTradeSetupEntity> getRecentExecutionsForUser(Long userId, List<String> statusList, Pageable pageable, String scope) {
        List<TriggeredTradeStatus> statuses = null;
        if (statusList != null && !statusList.isEmpty()) {
            statuses = statusList.stream()
                    .map(s -> {
                        try { return TriggeredTradeStatus.valueOf(s.toUpperCase()); }
                        catch (Exception e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
        }

        if (statuses != null && !statuses.isEmpty()) {
            List<String> statusNames = statuses.stream().map(Enum::name).collect(java.util.stream.Collectors.toList());
            if (isSimulatorDashboardScope(scope)) {
                return triggeredTradeRepo.findBySimulatorAndStatusIn(Broker.SIMULATOR.getDisplayName(), statusNames, unsortedPageable(pageable));
            }
            if (userId == null) {
                return triggeredTradeRepo.findByStatusIn(statuses, pageable);
            } else if (isUserDashboardScope(scope)) {
                return triggeredTradeRepo.findByAppUserIdAndStatusIn(userId, statuses, pageable);
            } else if (isAllDashboardScope(scope)) {
                return triggeredTradeRepo.findByAppUserIdOrSimulatorAndStatusIn(userId, Broker.SIMULATOR.getDisplayName(), statusNames, unsortedPageable(pageable));
            } else {
                return triggeredTradeRepo.findByAppUserIdExcludingSimulatorAndStatusIn(userId, Broker.SIMULATOR.getDisplayName(), statusNames, unsortedPageable(pageable));
            }
        } else {
            if (isSimulatorDashboardScope(scope)) {
                return triggeredTradeRepo.findBySimulator(Broker.SIMULATOR.getDisplayName(), unsortedPageable(pageable));
            }
            if (userId == null) {
                return triggeredTradeRepo.findAll(pageable);
            } else if (isUserDashboardScope(scope)) {
                return triggeredTradeRepo.findByAppUserId(userId, pageable);
            } else if (isAllDashboardScope(scope)) {
                return triggeredTradeRepo.findByAppUserIdOrSimulator(userId, Broker.SIMULATOR.getDisplayName(), unsortedPageable(pageable));
            } else {
                return triggeredTradeRepo.findByAppUserIdExcludingSimulator(userId, Broker.SIMULATOR.getDisplayName(), unsortedPageable(pageable));
            }
        }
    }

    private Pageable unsortedPageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.unpaged();
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    private boolean isSimulatorDashboardScope(String scope) {
        return scope != null && "simulator".equalsIgnoreCase(scope.trim());
    }

    private boolean isAllDashboardScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    private boolean isUserDashboardScope(String scope) {
        return scope != null && "user".equalsIgnoreCase(scope.trim());
    }

    public void subscribeForOpenTrades() {
        log.info("🚀 Starting to monitor trades...");
        // 1. Subscribe to LTP for all pending trade requests
        List<TriggerTradeRequestEntity> pendingRequests = triggerTradeRequestRepo
                .findByStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        log.info("📄 Found {} pending trade requests", pendingRequests.size());

        for (TriggerTradeRequestEntity request : pendingRequests) {
            try {
                Integer scripCode = request.getScripCode(); // Assuming you store this or convert symbol to code
                String feedKey = request.getExchange() + scripCode;
                boolean didSub = webSocketSubscriptionService.subscribeToScrip(feedKey);
                if (didSub) {
                    log.info("🔁 Subscribed to LTP for scrip {}", scripCode);
                } else {
                    log.debug("Already subscribed to LTP for scrip {} (ref++)", scripCode);
                }

                // Always subscribe to spot scrip if it exists
                if (request.getSpotScripCode() != null) {
                    ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(request.getSpotScripCode());
                    if (spotScript != null) {
                        String spotKey = spotFeedKey(spotScript);
                        if (isSharekhanIndexSpot(spotScript)
                                ? webSocketSubscriptionService.subscribeToScripLtp(spotKey)
                                : webSocketSubscriptionService.subscribeToScrip(spotKey)) {
                            log.info("🔁 Subscribed to spot LTP for request {} on scrip {} with spot key {}", request.getId(), scripCode, spotKey);
                        } else {
                            log.debug("Already subscribed to spot LTP for request {} on scrip {} with spot key {}", request.getId(), scripCode, spotKey);
                        }
                    } else {
                        log.warn("Could not find spot script master for scrip code {} on request {}", request.getSpotScripCode(), request.getId());
                    }
                }
            } catch (Exception e) {
                log.error("❌ Failed to subscribe LTP for trade request {}", request.getId(), e);
            }
        }

        // 2. Subscribe to ACK for all executed trades
        List<TriggeredTradeSetupEntity> executedTrades = triggeredTradeRepo.findByStatus(TriggeredTradeStatus.EXECUTED);
        List<TriggeredTradeSetupEntity> targetOrders = triggeredTradeRepo.findByStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
        java.util.Set<Long> seenTradeIds = new java.util.HashSet<>();
        List<TriggeredTradeSetupEntity> activeTrades = new java.util.ArrayList<>();

        for (TriggeredTradeSetupEntity trade : executedTrades) {
            if (trade != null && trade.getId() != null && seenTradeIds.add(trade.getId())) {
                activeTrades.add(trade);
            }
        }
        for (TriggeredTradeSetupEntity trade : targetOrders) {
            if (trade != null && trade.getId() != null && seenTradeIds.add(trade.getId())) {
                activeTrades.add(trade);
            }
        }

        if (!activeTrades.isEmpty()) {
            log.info("📄 Found {} active trades (EXECUTED/TARGET_ORDER_PLACED) for ACK monitoring", activeTrades.size());
            for (TriggeredTradeSetupEntity tradeSetupEntity : activeTrades) {
                try {
                    Integer scripCode = tradeSetupEntity.getScripCode(); // Assuming you store this or convert symbol to code
                    String feedKey = tradeSetupEntity.getExchange() + scripCode;
                    boolean didSub = webSocketSubscriptionService.subscribeToScrip(feedKey);
                    if (didSub) {
                        log.info("🔁 Subscribed to LTP for executed scrip {}", scripCode);
                    } else {
                        log.debug("Already subscribed to LTP for executed scrip {} (ref++)", scripCode);
                    }

                    // Always subscribe to spot scrip if it exists
                    if (tradeSetupEntity.getSpotScripCode() != null) {
                        ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(tradeSetupEntity.getSpotScripCode());
                        if (spotScript != null) {
                            String spotKey = spotFeedKey(spotScript);
                            if (isSharekhanIndexSpot(spotScript)
                                    ? webSocketSubscriptionService.subscribeToScripLtp(spotKey)
                                    : webSocketSubscriptionService.subscribeToScrip(spotKey)) {
                                log.info("🔁 Subscribed to spot LTP for executed trade {} on scrip {} with spot key {}", tradeSetupEntity.getId(), scripCode, spotKey);
                            } else {
                                log.debug("Already subscribed to spot LTP for executed trade {} on scrip {} with spot key {}", tradeSetupEntity.getId(), scripCode, spotKey);
                            }
                        } else {
                            log.warn("Could not find spot script master for scrip code {} on executed trade {}", tradeSetupEntity.getSpotScripCode(), tradeSetupEntity.getId());
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to subscribe LTP for trade request {}", tradeSetupEntity.getId(), e);
                }
            }
            java.util.Set<Long> ackCustomers = new java.util.HashSet<>();
            for (TriggeredTradeSetupEntity tradeSetupEntity : activeTrades) {
                try {
                    BrokerContext subCtx = resolveBrokerContext(tradeSetupEntity.getBrokerCredentialsId(), tradeSetupEntity.getAppUserId());
                    if (subCtx == null) {
                        continue;
                    }
                    if ("Simulator".equalsIgnoreCase(subCtx.getBrokerName())) {
                        continue;
                    }
                    try {
                        Broker broker = Broker.fromDisplayName(subCtx.getBrokerName());
                        if (broker != Broker.SHAREKHAN) {
                            continue;
                        }
                    } catch (Exception ignore) {
                        continue;
                    }
                    if (subCtx.getCustomerId() != null && ackCustomers.add(subCtx.getCustomerId())) {
                        webSocketSubscriptionService.subscribeToAck(String.valueOf(subCtx.getCustomerId()));
                        log.info("🔔 Subscribed to ACK feed for customer {}", subCtx.getCustomerId());
                    }
                } catch (Exception ackEx) {
                    log.warn("Failed subscribing to ACK for trade {}: {}", tradeSetupEntity.getId(), ackEx.getMessage());
                }
            }
            if (ackCustomers.isEmpty()) {
                log.debug("No eligible Sharekhan customers found for ACK subscription among {} active trades", activeTrades.size());
            }
        }

        log.info("✅ Monitoring setup complete.");

    }



    /**
     * Admin helper: execute a saved TriggerTradeRequestEntity (re-use existing execute flow)
     */
    public TriggeredTradeSetupEntity executeTradeFromEntity(TriggerTradeRequestEntity requestEntity) {
        return executeTradeFromEntity(requestEntity, false);
    }

    public TriggeredTradeSetupEntity executeTradeFromEntity(TriggerTradeRequestEntity requestEntity,
                                                            boolean chaseEntryUntilExecuted) {
        // If the saved request is missing broker credentials, resolve a sensible default
        try {
            if (requestEntity.getBrokerCredentialsId() == null) {
                Long resolved = null;
                Long uid = requestEntity.getAppUserId();
                if (uid != null) {
                    List<BrokerCredentialsEntity> list = brokerCredentialsRepository.findByAppUserId(uid);
                    BrokerCredentialsEntity chosen = choosePreferredBrokerCredential(list);
                    if (chosen != null) {
                        resolved = chosen.getId();
                    }
                }
                if (resolved == null) {
                    try {
                        List<BrokerCredentialsEntity> all = brokerCredentialsRepository.findAll();
                        BrokerCredentialsEntity chosen = choosePreferredBrokerCredential(all);
                        if (chosen != null) {
                            resolved = chosen.getId();
                        }
                    } catch (Exception ignore) { }
                }

                if (resolved != null) {
                    requestEntity.setBrokerCredentialsId(resolved);
                    // persist the resolution so subsequent actions don’t hit this path again
                    try { triggerTradeRequestRepository.save(requestEntity); } catch (Exception ignore) { }
                } else {
                    log.warn("No brokerCredentialsId on request {} and no suitable default could be resolved; proceeding without credentials.", requestEntity.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve broker credentials for request {}: {}", requestEntity != null ? requestEntity.getId() : null, e.toString());
        }

        // attempt to fetch current LTP for the scrip
        Integer optionScripCode = requestEntity.getScripCode();
        Double ltp = null;
        String exchange = requestEntity.getExchange();
        boolean isMxExchange = exchange != null && exchange.equalsIgnoreCase("MX");

        if (!isMxExchange) {
            ltp = ltpCacheService.getLtp(optionScripCode);

            if (ltp == null) {
                ltp = fetchLtpViaMStockFallback(optionScripCode, "executeTradeFromEntity");
            }

            if (ltp == null) {
                log.warn("Option LTP not found for scripCode {}. Skipping execution for trigger request {} this time.", optionScripCode, requestEntity.getId());
                return null; // Signal to the caller to skip and re-try later for equities.
            }

            if (!hasUsableTradedEntryPrice(requestEntity, ltp)) {
                log.warn("Entry delayed for trigger request {} because traded LTP {} looks like spot, not the traded option LTP. Waiting for correct option LTP.",
                        requestEntity.getId(), ltp);
                return null;
            }
        }

        if (ltp == null && isMxExchange) {
            Double manualEntry = requestEntity.getEntryPrice();
            if (manualEntry == null || manualEntry <= 0d) {
                log.warn("MX exchange request {} requires an entry price when LTP is unavailable. Skipping execution.", requestEntity.getId());
                return null;
            }
            ltp = manualEntry;
        }

        // Resolve flags considering legacy useSpotPrice
        boolean useSpotForEntry = Boolean.TRUE.equals(requestEntity.getUseSpotForEntry()) 
                || (requestEntity.getUseSpotForEntry() == null && Boolean.TRUE.equals(requestEntity.getUseSpotPrice()));
        
        boolean useSpotForSl = Boolean.TRUE.equals(requestEntity.getUseSpotForSl()) 
                || (requestEntity.getUseSpotForSl() == null && Boolean.TRUE.equals(requestEntity.getUseSpotPrice()));
        
        boolean useSpotForTarget = Boolean.TRUE.equals(requestEntity.getUseSpotForTarget()) 
                || (requestEntity.getUseSpotForTarget() == null && Boolean.TRUE.equals(requestEntity.getUseSpotPrice()));

        // build a temporary TriggeredTradeSetupEntity from the saved request so we can reuse the execute(...) method
        TriggeredTradeSetupEntity temp = new TriggeredTradeSetupEntity();
        temp.setTriggerRequestId(requestEntity.getId());
        temp.setScripCode(requestEntity.getScripCode());
        temp.setBrokerCredentialsId(requestEntity.getBrokerCredentialsId());
        temp.setAppUserId(requestEntity.getAppUserId());
        temp.setExchange(requestEntity.getExchange());
        temp.setSymbol(requestEntity.getSymbol());
        // Quantity in TriggeredTradeSetupEntity is Long; copy directly from request
        if (requestEntity.getQuantity() != null) {
            temp.setQuantity(requestEntity.getQuantity());
        }
        temp.setLots(requestEntity.getLots()); // Pass lots
        temp.setTslEnabled(requestEntity.getTslEnabled()); // Pass TSL flag
        temp.setInstrumentType(requestEntity.getInstrumentType());
        temp.setStrikePrice(requestEntity.getStrikePrice());
        temp.setOptionType(requestEntity.getOptionType());
        temp.setExpiry(requestEntity.getExpiry());
        temp.setIntraday(requestEntity.getIntraday());
        temp.setEntryPrice(requestEntity.getEntryPrice());
        temp.setStopLoss(requestEntity.getStopLoss());
        temp.setTarget1(requestEntity.getTarget1());
        temp.setTarget2(requestEntity.getTarget2());
        temp.setTarget3(requestEntity.getTarget3());
        temp.setTrailingSl(requestEntity.getTrailingSl());
        temp.setSource(requestEntity.getSource());
        
        temp.setUseSpotForEntry(useSpotForEntry);
        temp.setUseSpotForSl(useSpotForSl);
        temp.setUseSpotForTarget(useSpotForTarget);
        temp.setUseSpotPrice(requestEntity.getUseSpotPrice()); // Copy legacy flag

        temp.setSpotScripCode(requestEntity.getSpotScripCode());

        // run execution using the converted entity
        final double executionLtp = ltp;
        String requestLockKey = buildEntryLockKey(requestEntity);
        try {
            return orderPlacementGuard.withLock(requestLockKey, ORDER_LOCK_TIMEOUT,
                    () -> execute(temp, executionLtp, java.time.LocalDateTime.now(), chaseEntryUntilExecuted));
        } catch (OrderPlacementGuard.LockAcquisitionException e) {
            log.warn("⚠️ Duplicate entry placement prevented for request {} (symbol={}): {}",
                    requestEntity != null ? requestEntity.getId() : null,
                    requestEntity != null ? requestEntity.getSymbol() : "N/A",
                    e.getMessage());
            return null;
        }
    }

    private Double fetchLtpViaMStockFallback(Integer scripCode, String context) {
        if (scripCode == null) {
            return null;
        }
        try {
            ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
            if (script == null) {
                log.debug("[{}] No script master row found for scrip {} while attempting MStock LTP fallback.", context, scripCode);
                return null;
            }
            Optional<String> instrumentKeyOpt = mStockInstrumentResolver.resolveInstrumentKey(script);
            if (instrumentKeyOpt.isEmpty()) {
                log.warn("[{}] Unable to resolve MStock instrument key for scrip {} (symbol={}, exchange={}) during fallback LTP fetch.", context, scripCode, script.getTradingSymbol(), script.getExchange());
                return null;
            }
            String instrumentKey = instrumentKeyOpt.get();
            log.debug("[{}] Attempting MStock LTP fetch for instrument {} (scrip {}).", context, instrumentKey, scripCode);
            Map<String, Object> payload = mStockLtpService.fetchLtpForInstrument(instrumentKey);
            if (payload == null) {
                log.warn("[{}] MStock LTP API returned null data for instrument {} (scrip {}).", context, instrumentKey, scripCode);
                return null;
            }
            Object lastPriceObj = payload.get("last_price");
            if (lastPriceObj instanceof Number number) {
                double fetchedLtp = number.doubleValue();
                ltpCacheService.updateLtp(scripCode, fetchedLtp);
                log.info("[{}] Fetched LTP {} via MStock for scrip {} (instrument {}).", context, fetchedLtp, scripCode, instrumentKey);
                return fetchedLtp;
            }
            log.warn("[{}] MStock LTP API returned missing last_price for instrument {} (scrip {}). Payload={}", context, instrumentKey, scripCode, payload);
        } catch (Exception ex) {
            log.warn("[{}] Failed to fetch fallback LTP from MStock for scrip {}: {}", context, scripCode, ex.getMessage());
            log.debug("[{}] MStock fallback stacktrace", context, ex);
        }
        return null;
    }

    private String buildEntryLockKey(TriggerTradeRequestEntity request) {
        if (request == null) {
            return "ENTRY:REQ:NULL";
        }
        if (request.getId() != null) {
            return "ENTRY:REQ:" + request.getId();
        }
        return "ENTRY:REQ:" +
                Objects.toString(request.getAppUserId(), "NA") + ":" +
                Objects.toString(request.getSymbol(), "NA") + ":" +
                Objects.toString(request.getStrikePrice(), "NA") + ":" +
                Objects.toString(request.getOptionType(), "NA");
    }

    private String buildEntryLockKey(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return "ENTRY:TRADE:NULL";
        }
        if (trade.getId() != null) {
            return "ENTRY:TRADE:" + trade.getId();
        }
        if (trade.getOrderId() != null && !trade.getOrderId().isBlank()) {
            return "ENTRY:ORDER:" + trade.getOrderId();
        }
        return "ENTRY:TRADE:" +
                Objects.toString(trade.getAppUserId(), "NA") + ":" +
                Objects.toString(trade.getSymbol(), "NA") + ":" +
                Objects.toString(trade.getStrikePrice(), "NA") + ":" +
                Objects.toString(trade.getOptionType(), "NA");
    }

    private String buildExitLockKey(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return "EXIT:TRADE:NULL";
        }
        if (trade.getId() != null) {
            return "EXIT:TRADE:" + trade.getId();
        }
        if (trade.getExitOrderId() != null && !trade.getExitOrderId().isBlank()) {
            return "EXIT:ORDER:" + trade.getExitOrderId();
        }
        return "EXIT:TRADE:" +
                Objects.toString(trade.getAppUserId(), "NA") + ":" +
                Objects.toString(trade.getSymbol(), "NA") + ":" +
                Objects.toString(trade.getStrikePrice(), "NA") + ":" +
                Objects.toString(trade.getOptionType(), "NA");
    }

    private boolean isBrokerContextUsable(BrokerContext ctx) {
        if (ctx == null || ctx.getBrokerName() == null) {
            return false;
        }
        try {
            Broker broker = Broker.fromDisplayName(ctx.getBrokerName());
            if (broker == Broker.SIMULATOR) {
                return true;
            }
            if (ctx.getApiKey() == null) {
                return false;
            }
            return broker != Broker.SHAREKHAN || ctx.getCustomerId() != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- Broker context resolution ----------------
    private BrokerContext resolveBrokerContext(Long brokerCredentialsId, Long appUserId) {
        try {
            org.com.sharekhan.entity.BrokerCredentialsEntity chosen = null;
            if (brokerCredentialsId != null) {
                chosen = brokerCredentialsRepository.findById(brokerCredentialsId).orElse(null);
            }
            if (chosen == null && appUserId != null) {
                java.util.List<org.com.sharekhan.entity.BrokerCredentialsEntity> list = brokerCredentialsRepository.findByAppUserId(appUserId);
                chosen = choosePreferredBrokerCredential(list);
            }
            if (chosen == null) {
                java.util.List<org.com.sharekhan.entity.BrokerCredentialsEntity> all = brokerCredentialsRepository.findAll();
                chosen = choosePreferredBrokerCredential(all);
            }
            if (chosen == null) return null;

            Long customerId = chosen.getCustomerId();
            String apiKey = null;
            String clientCode = null;
            try { apiKey = cryptoService.decrypt(chosen.getApiKey()); } catch (Exception e) { apiKey = chosen.getApiKey(); }
            try { clientCode = cryptoService.decrypt(chosen.getClientCode()); } catch (Exception e) { clientCode = chosen.getClientCode(); }
            return new BrokerContext(customerId, apiKey, clientCode, chosen.getBrokerName(), chosen.getId());
        } catch (Exception e) {
            log.warn("Failed to resolve broker context: {}", e.toString());
            return null;
        }
    }

    private BrokerCredentialsEntity choosePreferredBrokerCredential(List<BrokerCredentialsEntity> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        BrokerCredentialsEntity defaultOrderBroker = credentials.stream()
                .filter(this::isActiveCredential)
                .filter(this::isOrderRoutingEnabled)
                .filter(credential -> Boolean.TRUE.equals(credential.getDefaultForOrders()))
                .findFirst()
                .orElse(null);
        if (defaultOrderBroker != null) {
            return defaultOrderBroker;
        }
        BrokerCredentialsEntity activeSharekhan = firstCredential(credentials, Broker.SHAREKHAN, true);
        if (activeSharekhan != null) {
            return activeSharekhan;
        }
        BrokerCredentialsEntity anySharekhan = firstCredential(credentials, Broker.SHAREKHAN, false);
        if (anySharekhan != null) {
            return anySharekhan;
        }
        for (BrokerCredentialsEntity credential : credentials) {
            if (credential != null && Boolean.TRUE.equals(credential.getActive())) {
                return credential;
            }
        }
        return credentials.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private BrokerCredentialsEntity firstCredential(List<BrokerCredentialsEntity> credentials,
                                                    Broker broker,
                                                    boolean activeOnly) {
        for (BrokerCredentialsEntity credential : credentials) {
            if (credential == null || credential.getBrokerName() == null) {
                continue;
            }
            if (credential.getBrokerName().equalsIgnoreCase(broker.getDisplayName())
                    && (!activeOnly || Boolean.TRUE.equals(credential.getActive()))) {
                return credential;
            }
        }
        return null;
    }

    private boolean isActiveCredential(BrokerCredentialsEntity credential) {
        return credential != null && !Boolean.FALSE.equals(credential.getActive());
    }

    private boolean isOrderRoutingEnabled(BrokerCredentialsEntity credential) {
        if (!isActiveCredential(credential)) {
            return false;
        }
        if (credential.getTradingEnabled() != null) {
            return credential.getTradingEnabled();
        }
        try {
            Broker broker = Broker.fromDisplayName(credential.getBrokerName());
            return broker == Broker.SHAREKHAN || broker == Broker.SIMULATOR;
        } catch (Exception e) {
            return false;
        }
    }
}
