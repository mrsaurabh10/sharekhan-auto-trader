package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.model.OrderParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.exception.InvalidTradeRequestException;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionService {

    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final TriggerTradeRequestRepository triggerTradeRequestRepo;
    private final TokenStoreService tokenStoreService; // ✅ holds current token
    private final LtpCacheService ltpCacheService;

    private final ApplicationEventPublisher eventPublisher;

    private final WebSocketSubscriptionService webSocketSubscriptionService;

    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final ScriptMasterRepository scriptMasterRepository;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @Autowired
    private UserConfigService userConfigService;

    private final CryptoService cryptoService;

    @Autowired
    private TelegramNotificationService telegramNotificationService;

    @PersistenceContext
    private EntityManager entityManager;


    // Backwards compatible public API - by default allow service to compute quantity if missing
    public TriggerTradeRequestEntity executeTrade(TriggerRequest request) {
        return executeTrade(request, false);
    }

    // New overload: if requireQuantity==true, throw an exception and send telegram when quantity is null
    public TriggerTradeRequestEntity executeTrade(TriggerRequest request, boolean requireQuantity) {


        // Initialize formatter for expiry date parsing
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalTime cutoff = LocalTime.of(15, 30); // 3:30 PM IST

        // Determine exchange and whether it's a no-strike exchange (NC/BC)
        final String exch = request.getExchange() == null ? null : request.getExchange().toUpperCase();
        final boolean isNoStrikeExchange = exch != null && (exch.equals("NC") || exch.equals("BC"));

        // Handle null expiry: for regular option trades (NOT NC/BC and strikePrice != 0.0) attempt to select nearest valid expiry
        if (!isNoStrikeExchange && request.getExpiry() == null && request.getStrikePrice() != null && Double.compare(request.getStrikePrice(), 0.0) != 0) {
            List<String> allExpiryStrings = scriptMasterRepository.findAllExpiriesByTradingSymbolAndStrikePriceAndOptionType(
                    request.getInstrument(),
                    request.getStrikePrice(),
                    request.getOptionType()
            );

            // Parse expiry strings to LocalDate objects and pick the nearest valid one
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
                        // Keep only expiry dates that are today but before cutoff or future dates
                        return expiryDate.isAfter(now.toLocalDate()) ||
                                (expiryDate.isEqual(now.toLocalDate()) && now.isBefore(expiryCutoff));
                    })
                    .min(Comparator.naturalOrder());  // Pick nearest valid expiry instead of max


            if (latestExpiryOpt.isPresent()) {
                // Set expiry in request as string in dd/MM/yyyy format
                String latestExpiryStr = latestExpiryOpt.get().format(formatter);
                request.setExpiry(latestExpiryStr);
            } else {
                throw new RuntimeException("No valid expiry found for the given instrument and strike price");
            }
        }

        ScriptMasterEntity script;
        // If exchange is NC or BC, treat this as an equity/no-strike instrument and lookup by exchange + tradingSymbol
        if (isNoStrikeExchange) {
            // Try exact match first
            Optional<ScriptMasterEntity> opt = scriptMasterRepository.findByExchangeAndTradingSymbolAndStrikePriceIsNullAndExpiryIsNull(
                    exch, request.getInstrument()
            );
            if (opt.isPresent()) {
                script = opt.get();
            } else {
                // Fallback: try case-insensitive fetch of all rows for the exchange and match tradingSymbol ignoring case
                List<ScriptMasterEntity> allForExchange = scriptMasterRepository.findByExchangeIgnoreCase(exch);
                if (allForExchange == null) allForExchange = List.of();
                Optional<ScriptMasterEntity> match = allForExchange.stream()
                        .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(request.getInstrument()))
                        .filter(s -> (s.getStrikePrice() == null || Double.compare(s.getStrikePrice(), 0.0) == 0)
                                && (s.getExpiry() == null || s.getExpiry().isBlank()))
                        .findFirst();
                if (match.isPresent()) {
                    script = match.get();
                } else {
                    // Last effort: try any tradingSymbol match for the exchange (ignore strike/expiry) and accept if instrument matches
                    Optional<ScriptMasterEntity> anyMatch = allForExchange.stream()
                            .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(request.getInstrument()))
                            .findFirst();
                    if (anyMatch.isPresent()) {
                        script = anyMatch.get();
                    } else {
                        throw new RuntimeException("Script not found in master DB for instrument on exchange " + exch + " (strike & expiry null)");
                    }
                }
            }
        } else {
            // Regular option/future lookup (strike and expiry expected)
            script = scriptMasterRepository.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(
                    request.getInstrument(),
                    request.getStrikePrice(),
                    request.getOptionType(),
                    request.getExpiry()
            ).orElseThrow(() -> new RuntimeException("Script not found in master DB"));
        }


        if (request.getQuantity() == null) {
            // Prefer per-user configuration via appUserId; fallback defaults are provided
            Long appUserId = request.getUserId();
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
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .createdAt(LocalDateTime.now())
                .intraday(request.getIntraday())
                .brokerCredentialsId(request.getBrokerCredentialsId())
                .appUserId(request.getUserId())
                .build();

        // Persist request entity (no more storing legacy customerId on the request)
        TriggerTradeRequestEntity saved = triggerTradeRequestRepository.save(entity);
        String key = entity.getExchange() + entity.getScripCode();
        webSocketSubscriptionService.subscribeToScrip(key);
        return saved;
    }

    public boolean moveStopLossToCost(Long tradeId) {
        TriggeredTradeSetupEntity tradeSetupEntity = triggeredTradeRepo.findById(tradeId).orElse(null);
        if (tradeSetupEntity == null) return false;

        tradeSetupEntity.setStopLoss(tradeSetupEntity.getEntryPrice());
        triggeredTradeRepo.save(tradeSetupEntity);
        return true;
    }

    public TriggeredTradeSetupEntity execute(TriggeredTradeSetupEntity trigger, double ltp) {
        // Backwards-compatible execute: record triggeredAt as now
        return execute(trigger, ltp, java.time.LocalDateTime.now());
    }

    // New overload: allow caller to pass exact triggeredAt (time when entry condition met)
    public TriggeredTradeSetupEntity execute(TriggeredTradeSetupEntity trigger, double ltp, java.time.LocalDateTime triggeredAt) {
        try {
            // Safety guard: do not attempt broker placement if quantity is null/zero
            if (trigger.getQuantity() == null || trigger.getQuantity() <= 0L) {
                log.warn("Blocking placement for trigger {} due to non-positive quantity: {}", trigger.getId(), trigger.getQuantity());
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
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
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
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
                return rejected;
            }
            // Prefer a customer specific token when placing the order
            Long custId = null;
            String clientCode = "";
            String apiKey = "";
            if (trigger.getBrokerCredentialsId() != null) {
                Optional<BrokerCredentialsEntity> brokerCredentialsEntity =    brokerCredentialsRepository.
                        findById(trigger.getBrokerCredentialsId());
                if(brokerCredentialsEntity.isPresent()){
                    custId = brokerCredentialsEntity.get().getCustomerId();
                    clientCode =   cryptoService.decrypt(brokerCredentialsEntity.get().getClientCode()) ;
                    apiKey =cryptoService.decrypt(brokerCredentialsEntity.get().getApiKey()) ;
                }
            }
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, custId);
            if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            SharekhanConnect sharekhanConnect = new SharekhanConnect(null, apiKey, accessToken);

            // 🧾 Build order request
            OrderParams order = new OrderParams();
            // Use the customerId from broker credentials when available; otherwise fall back to default
            order.customerId = custId != null ? custId : TokenLoginAutomationService.customerId;
            order.scripCode =  trigger.getScripCode();
            order.tradingSymbol = trigger.getSymbol();
            order.exchange = trigger.getExchange();
            order.transactionType = "B";
            order.quantity = trigger.getQuantity();
            // For some broad-market indices/equity feeds we send price=0.0 to indicate a market order
            String sym = trigger.getSymbol() != null ? trigger.getSymbol().toUpperCase() : "";
            final Set<String> MARKET_SYMBOLS = Set.of("SENSEX", "NIFTY", "BANKNIFTY", "BANKEX", "FINNIFTY");
            if (MARKET_SYMBOLS.contains(sym)) {
                order.price = "0.0";
            } else {
                order.price = String.valueOf(ltp); //choosing ltp as higher chances of execution
            }
            order.quantity = Long.valueOf(trigger.getQuantity());
            order.price =  String.valueOf(ltp); //choosing ltp as higher chances of execution
            order.orderType = "NORMAL";
            order.productType = "INVESTMENT";
            order.instrumentType = trigger.getInstrumentType(); //FUTCUR, FS, FI, OI, OS, FUTCURR, OPTCURR
            if (trigger.getStrikePrice() != null) {
                order.strikePrice = String.valueOf(trigger.getStrikePrice());
            } else {
                order.strikePrice = null;
            }
            order.optionType = (trigger.getOptionType() != null && !trigger.getOptionType().isBlank()) ? trigger.getOptionType() : null;
            order.expiry = (trigger.getExpiry() != null && !trigger.getExpiry().isBlank()) ? trigger.getExpiry() : null;
            order.requestType = "NEW";
            order.afterHour =  "N";
            order.validity = "GFD";
            order.rmsCode ="ANY";
            order.disclosedQty = 0L;
            order.channelUser = clientCode ;//TokenLoginAutomationService.clientCode;

            // Retry logic: attempt up to 3 times when broker response doesn't return an orderId.
            JSONObject response = null;
            String orderId = null;
            final int maxAttempts = 3;
            long[] backoffMs = new long[]{300L, 700L, 1500L};

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    response = sharekhanConnect.placeOrder(order);
                } catch (Exception e) {
                    log.warn("Attempt {}: placeOrder threw exception for trigger {}: {}", attempt, trigger.getId(), e.getMessage());
                }

                // Compact logging per attempt
                try {
                    if (response != null && response.has("data")) {
                        JSONObject d = response.getJSONObject("data");
                        String respOrderId = d.optString("orderId", d.optString("orsOrderId", null));
                        String respStatus = d.optString("orderStatus", "");
                        String respAvg = d.optString("avgPrice", d.optString("orderPrice", ""));
                        String respExecQty = d.has("execQty") ? String.valueOf(d.optInt("execQty")) : d.optString("execQty", "");
                        log.info("Sharekhan placeOrder attempt {} summary: orderId={} status={} avg/price={} execQty={}", attempt, respOrderId, respStatus, respAvg, respExecQty);
                        if (respOrderId != null && !respOrderId.isBlank()) {
                            orderId = respOrderId;
                        }
                    } else if (response != null) {
                        log.info("Sharekhan placeOrder attempt {} received response but missing data object: status={}", attempt, response.optInt("status", -1));
                    } else {
                        log.info("Sharekhan placeOrder attempt {} returned null response", attempt);
                    }
                } catch (Exception e) {
                    log.debug("Failed to compactly log placeOrder attempt {} response: {}", attempt, e.getMessage());
                }

                if (orderId != null) break; // success

                // If not last attempt, wait before retrying
                if (attempt < maxAttempts) {
                    long sleepMs = backoffMs[Math.min(attempt-1, backoffMs.length-1)];
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (orderId == null) {
                // After retries, still no orderId — mark as rejected, persist a rejected trade entity and notify
                log.error("❌ Place order failed after {} attempts for trigger {}. Response: {}", maxAttempts, trigger.getId(), response);
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                rejected.setStatus(TriggeredTradeStatus.REJECTED);
                rejected.setExitReason("Broker did not return orderId after retries");
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
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
                try {
                    triggeredTradeRepo.save(rejected);
                    webSocketSubscriptionService.unsubscribeFromScrip(trigger.getExchange() + trigger.getScripCode());
                } catch (Exception e) {
                    log.warn("Failed to persist rejected triggered trade after placeOrder failures: {}", e.getMessage());
                }

                try {
                    String title = "Order Placement Failed after retries ❌";
                    StringBuilder body = new StringBuilder();
                    body.append("Instrument: ").append(trigger.getSymbol()).append("\n");
                    body.append("Exchange: ").append(trigger.getExchange()).append("\n");
                    body.append("Attempted Qty: ").append(trigger.getQuantity()).append("\n");
                    body.append("Attempted Price(LTP): ").append(ltp).append("\n");
                    body.append("Trigger Entity Id: ").append(trigger.getId()).append("\n");
                    body.append("Note: PlaceOrder did not return orderId after " ).append(maxAttempts).append(" attempts.");
                    telegramNotificationService.sendTradeMessageForUser(trigger.getAppUserId(), title, body.toString());
                } catch (Exception e) {
                    log.warn("Failed sending telegram notification for placeOrder failure: {}", e.getMessage());
                }

                return rejected; // abort with rejected entity
            }

            // order placed attempt completed, response received
            log.info("✅ Sharekhan placeOrder attempt completed. orderId={}", orderId);
            log.info("Response received " + response);
            if (response == null) {
                log.error("❌ Sharekhan order failed or returned null for trigger {}", trigger.getId());
                // Treat null response as immediate rejection
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                rejected.setStatus(TriggeredTradeStatus.REJECTED);
                rejected.setExitReason("Broker returned no response while placing order");
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
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
                triggeredTradeRepo.save(rejected);
                return rejected;
            }

            // Handle explicit error object from broker, e.g. {"errorType":"input_error","message":"...","status":400}
            try {
                String errorType = response.optString("errorType", null);
                Integer httpStatus = response.has("status") ? response.optInt("status", 0) : null;
                if ((errorType != null && !errorType.isBlank()) || (httpStatus != null && httpStatus >= 400)) {
                    log.warn("❌ Broker rejected order for trigger {}: type={}, status={}, message={} ",
                            trigger.getId(), errorType, httpStatus, response.optString("message", ""));
                    TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                    rejected.setStatus(TriggeredTradeStatus.REJECTED);
                    StringBuilder reason = new StringBuilder();
                    if (errorType != null && !errorType.isBlank()) {
                        reason.append(errorType);
                    }
                    String msg = response.optString("message", "");
                    if (msg != null && !msg.isBlank()) {
                        if (!reason.isEmpty()) reason.append(": ");
                        reason.append(msg);
                    }
                    if (httpStatus != null && httpStatus >= 400) {
                        if (!reason.isEmpty()) reason.append(" (status ").append(httpStatus).append(")");
                        else reason.append("HTTP ").append(httpStatus);
                    }
                    if (reason.isEmpty()) reason.append("Broker rejected the order");
                    rejected.setExitReason(reason.toString());
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
                    rejected.setTarget3(trigger.getTarget3());
                    rejected.setInstrumentType(trigger.getInstrumentType());
                    rejected.setEntryPrice(ltp);
                    rejected.setOptionType(trigger.getOptionType());
                    rejected.setIntraday(trigger.getIntraday());
                    triggeredTradeRepo.save(rejected);
                    return rejected;
                }
            } catch (Exception ignore) { /* proceed to other parsing */ }

            if (!response.has("data")){
                log.error("❌ Sharekhan order failed or returned without data for trigger {}: {}", trigger.getId(), response);
                // Treat missing data as rejection as well
                TriggeredTradeSetupEntity rejected = new TriggeredTradeSetupEntity();
                rejected.setStatus(TriggeredTradeStatus.REJECTED);
                rejected.setExitReason("Broker response missing data field");
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
                rejected.setTarget3(trigger.getTarget3());
                rejected.setInstrumentType(trigger.getInstrumentType());
                rejected.setEntryPrice(ltp);
                rejected.setOptionType(trigger.getOptionType());
                rejected.setIntraday(trigger.getIntraday());
                triggeredTradeRepo.save(rejected);
                return rejected;
            } else if (response.getJSONObject("data").has("orderId")){
                String orderIdRaw = response.getJSONObject("data").optString("orderId", null);
                orderId = (orderIdRaw != null ? orderIdRaw.trim() : null);
                // Some broker responses return "0" or blank when an order id isn't yet assigned. Treat such values as null
                if (orderId == null || orderId.isBlank() || orderId.equals("0") || orderId.equalsIgnoreCase("null") || orderId.equalsIgnoreCase("na")) {
                    log.warn("⚠️ Broker returned a non-real orderId ({}). Persisting null to avoid unique key collisions.", orderIdRaw);
                    orderId = null;
                }
                //order placed  successfully
                log.info("✅ Sharekhan order placed successfully: {}", response.toString(2));

                // check the status with a delay of .5 second
                //Thread.sleep(500);

                TriggeredTradeSetupEntity triggeredTradeSetupEntity = new TriggeredTradeSetupEntity();
                if (orderId != null) {
                    triggeredTradeSetupEntity.setOrderId(orderId);
                }

                triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
                // mark the time when this trigger was converted into a live trade (trigger fired -> order placed)
                triggeredTradeSetupEntity.setTriggeredAt(triggeredAt != null ? triggeredAt : java.time.LocalDateTime.now());
                // If broker returned placeholder/invalid orderId (treated as null), mark as REJECTED as per requirement
                if (orderId == null) {
                    triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.REJECTED);
                    triggeredTradeSetupEntity.setExitReason("Broker returned invalid orderId (0/blank)");
                } else {
                    triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
                }

                triggeredTradeSetupEntity.setScripCode(trigger.getScripCode());
                triggeredTradeSetupEntity.setExchange(trigger.getExchange());
                triggeredTradeSetupEntity.setSymbol(trigger.getSymbol());
                triggeredTradeSetupEntity.setExpiry(trigger.getExpiry());
                triggeredTradeSetupEntity.setStrikePrice(trigger.getStrikePrice());
                triggeredTradeSetupEntity.setStopLoss(trigger.getStopLoss());
                triggeredTradeSetupEntity.setTarget1(trigger.getTarget1());
                triggeredTradeSetupEntity.setTarget2(trigger.getTarget2());
                // trigger.getQuantity() already stores the final quantity (shares) as Long
                triggeredTradeSetupEntity.setQuantity(trigger.getQuantity());
                triggeredTradeSetupEntity.setTarget3(trigger.getTarget3());
                triggeredTradeSetupEntity.setInstrumentType(trigger.getInstrumentType());
                triggeredTradeSetupEntity.setEntryPrice(ltp);
                triggeredTradeSetupEntity.setOptionType(trigger.getOptionType());
                triggeredTradeSetupEntity.setIntraday(trigger.getIntraday());
                // attach broker credentials id and app user id from the trigger for traceability
                triggeredTradeSetupEntity.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
                triggeredTradeSetupEntity.setAppUserId(trigger.getAppUserId());
                triggeredTradeRepo.save(triggeredTradeSetupEntity);
                triggeredTradeSetupEntity.setScripCode(trigger.getScripCode());
                triggeredTradeSetupEntity.setExchange(trigger.getExchange());
                // attach broker credentials id and app user id from the trigger for traceability
                triggeredTradeSetupEntity.setBrokerCredentialsId(trigger.getBrokerCredentialsId());
                triggeredTradeSetupEntity.setAppUserId(trigger.getAppUserId());
                triggeredTradeSetupEntity.setSymbol(trigger.getSymbol());
                triggeredTradeSetupEntity.setExpiry(trigger.getExpiry());
                triggeredTradeSetupEntity.setStrikePrice(trigger.getStrikePrice());
                triggeredTradeSetupEntity.setStopLoss(trigger.getStopLoss());
                triggeredTradeSetupEntity.setTarget1(trigger.getTarget1());
                triggeredTradeSetupEntity.setTarget2(trigger.getTarget2());
                triggeredTradeSetupEntity.setQuantity(trigger.getQuantity());
                triggeredTradeSetupEntity.setTarget3(trigger.getTarget3());
                triggeredTradeSetupEntity.setInstrumentType(trigger.getInstrumentType());
                triggeredTradeSetupEntity.setEntryPrice(ltp);
                triggeredTradeSetupEntity.setOptionType(trigger.getOptionType());
                triggeredTradeSetupEntity.setIntraday(trigger.getIntraday());
                triggeredTradeRepo.save(triggeredTradeSetupEntity);

                // Publish event AFTER transaction commit so pollers/readers see the committed DB state
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eventPublisher.publishEvent(new OrderPlacedEvent(triggeredTradeSetupEntity));
                        }
                    });
                } else {
                    eventPublisher.publishEvent(new OrderPlacedEvent(triggeredTradeSetupEntity));
                }

            //dont need the unsubscribe code here as we will not unsubscribe
            // reason being need to take care of pending order
           // String feedKey = trigger.getExchange() + trigger.getScripCode();
            //webSocketSubscriptionService.unsubscribeFromScrip(feedKey);


                log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trigger.getScripCode(), ltp);
                // Only publish order placed event and proceed with live monitoring when order is actually placed
                if (triggeredTradeSetupEntity.getStatus() != TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION) {
                    log.warn("❌ Marked trade as REJECTED due to invalid/zero orderId for scripCode {}", trigger.getScripCode());
                }
                return triggeredTradeSetupEntity;
            }

        } catch (Exception e) {
            log.error("❌ Error executing trade for trigger {}: {}", trigger.getId(), e.getMessage(), e);
        }
        return null;
    }

////    public void markOrderExecuted(String orderId) {
////        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findByOrderId(orderId)
////                .orElseThrow(() -> new RuntimeException("Trade not found"));
////
////        trade.setStatus(TriggeredTradeStatus.EXECUTED);
////        triggeredTradeRepo.save(trade);
////
////        // ✅ Start LTP monitoring
////        String feedKey = trade.getExchange() + trade.getScripCode();
////        webSocketSubscriptionService.subscribeToScrip(feedKey);
////
////        log.info("✅ Order executed, monitoring LTP for SL/target: {}", feedKey);
////    }

    public void markOrderRejected(String orderId) {
        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Trade not found"));

        trade.setStatus(TriggeredTradeStatus.REJECTED);
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
        java.util.List<TriggeredTradeSetupEntity> trades = triggeredTradeRepo.findByScripCodeAndStatus(scripCode, TriggeredTradeStatus.EXECUTED);

        if (trades != null && !trades.isEmpty()) {
            TriggeredTradeSetupEntity trade = trades.get(0);

            Double ltp = ltpCacheService.getLtp(scripCode);
            double exitPrice = (ltp != null) ? ltp : trade.getEntryPrice(); // fallback to entry

            log.info("🛑 Force-closing trade {} at price: {}", trade.getId(), exitPrice);

            // delegate directly to OrderExitService to avoid self-invocation of transactional logic
            orderExitService.performSquareOff(trade, exitPrice, "Force fully close the trade"); // reuse existing logic
            return true;
        } else {
            return false;
        }
    }

    @Autowired
    private OrderExitService orderExitService;

    public void squareOff(TriggeredTradeSetupEntity trade, double exitPrice, String exitReason) {
        // Re-load the trade from DB (within transaction) to avoid races. Claim the trade by setting status
        TriggeredTradeSetupEntity persisted = triggeredTradeRepo.findById(trade.getId())
                .orElseThrow(() -> new RuntimeException("Trade not found: " + trade.getId()));

        // Prefer stricter claim: if current status is EXIT_TRIGGERED then move to EXIT_ORDER_PLACED.
        // Otherwise (e.g., forceClose) allow transition from EXECUTED -> EXIT_ORDER_PLACED.
        int claimed = 0;
        try {
            // Try the normal flow: EXIT_TRIGGERED -> EXIT_ORDER_PLACED
            claimed = triggeredTradeRepo.claimIfStatusEquals(persisted.getId(), TriggeredTradeStatus.EXIT_TRIGGERED.name(), TriggeredTradeStatus.EXIT_ORDER_PLACED.name(), exitReason);
            if (claimed == 0) {
                // fallback: try EXECUTED -> EXIT_ORDER_PLACED (handles force-close and edge cases)
                claimed = triggeredTradeRepo.claimIfStatusEquals(persisted.getId(), TriggeredTradeStatus.EXECUTED.name(), TriggeredTradeStatus.EXIT_ORDER_PLACED.name(), exitReason);
            }
        } catch (Exception e) {
            log.warn("Failed to claim exit for trade {}: {}", persisted.getId(), e.getMessage());
        }

        if (claimed == 0) {
            // someone else already claimed or trade in terminal state
            log.info("⚠️ Square-off skipped: trade {} could not be claimed for exit (already claimed/terminal)", persisted.getId());
            return;
        }

        // Persist exitReason so we record why we initiated a square-off. Do NOT mark EXIT_ORDER_PLACED until we have an exitOrderId.
        try {
            persisted.setExitReason(exitReason);
            triggeredTradeRepo.save(persisted);
        } catch (Exception e) {
            log.debug("Failed to persist exitReason for trade {}: {}", persisted.getId(), e.getMessage());
        }

        // reload the persisted state after claim to ensure we have latest values
        persisted = triggeredTradeRepo.findById(trade.getId())
                .orElseThrow(() -> new RuntimeException("Trade not found after claim: " + trade.getId()));

        // Build exit order params using persisted (fresh) values
        OrderParams exitOrder = new OrderParams();
        // Resolve customerId from broker credentials if available; fallback to default
        Long exitCustId = null;
        try {
            if (persisted.getBrokerCredentialsId() != null) {
                exitCustId = brokerCredentialsRepository.findById(persisted.getBrokerCredentialsId())
                        .map(BrokerCredentialsEntity::getCustomerId)
                        .orElse(null);
            }
        } catch (Exception ignore) { }
        exitOrder.customerId = (exitCustId != null ? exitCustId : TokenLoginAutomationService.customerId);
        exitOrder.exchange = persisted.getExchange();
        exitOrder.scripCode = persisted.getScripCode();
        // For equities (NC/BC) strikePrice/optionType/expiry may be null — send null instead of "null"
        if (persisted.getStrikePrice() != null) {
            exitOrder.strikePrice = String.valueOf(persisted.getStrikePrice());
        } else {
            exitOrder.strikePrice = null;
        }
        exitOrder.optionType = (persisted.getOptionType() != null && !persisted.getOptionType().isBlank()) ? persisted.getOptionType() : null;
        exitOrder.tradingSymbol = persisted.getSymbol();
        exitOrder.expiry = (persisted.getExpiry() != null && !persisted.getExpiry().isBlank()) ? persisted.getExpiry() : null;
        exitOrder.quantity = persisted.getQuantity().longValue();
        exitOrder.instrumentType = persisted.getInstrumentType();
        exitOrder.productType = "INVESTMENT";
        // For some broad-market indices/equity feeds we send price=0.0 to indicate a market order
        String persistedSym = persisted.getSymbol() != null ? persisted.getSymbol().toUpperCase() : "";
        final Set<String> EXIT_MARKET_SYMBOLS = Set.of("SENSEX", "NIFTY", "BANKNIFTY", "BANKEX", "FINNIFTY");
        if (EXIT_MARKET_SYMBOLS.contains(persistedSym)) {
            exitOrder.price = "0.0";
        } else {
            exitOrder.price = String.valueOf(exitPrice);
        }
        exitOrder.transactionType = "S";
        exitOrder.orderType = "NORMAL";
        // expiry already set above conditionally
        exitOrder.requestType = "NEW";
        exitOrder.afterHour = "N";
        exitOrder.validity = "GFD";
        exitOrder.rmsCode = "ANY";
        exitOrder.disclosedQty = 0L;
        exitOrder.channelUser = TokenLoginAutomationService.clientCode;

        String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
        SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);

        // Retry logic for exit order placement
        JSONObject response = null;
        String exitOrderId = null;
        final int maxAttempts = 3;
        long[] backoffMs = new long[]{300L, 700L, 1500L};

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                response = sharekhanConnect.placeOrder(exitOrder);
            } catch (Exception e) {
                log.warn("Exit attempt {}: placeOrder threw exception for trade {}: {}", attempt, persisted.getId(), e.getMessage());
            }

            try {
                if (response != null && response.has("data")) {
                    JSONObject d = response.getJSONObject("data");
                    String respOrderId = d.optString("orderId", d.optString("orsOrderId", null));
                    String respStatus = d.optString("orderStatus", "");
                    String respAvg = d.optString("avgPrice", d.optString("orderPrice", ""));
                    String respExecQty = d.has("execQty") ? String.valueOf(d.optInt("execQty")) : d.optString("execQty", "");
                    log.info("Sharekhan exit placeOrder attempt {} summary: orderId={} status={} avg/price={} execQty={}", attempt, respOrderId, respStatus, respAvg, respExecQty);
                    if (respOrderId != null && !respOrderId.isBlank()) {
                        exitOrderId = respOrderId;
                    }
                } else if (response != null) {
                    log.info("Sharekhan exit placeOrder attempt {} received response but missing data object: status={}", attempt, response.optInt("status", -1));
                } else {
                    log.info("Sharekhan exit placeOrder attempt {} returned null response for trade {}", attempt, persisted.getId());
                }
            } catch (Exception e) {
                log.debug("Failed to compactly log exit placeOrder attempt {} response: {}", attempt, e.getMessage());
            }

            if (exitOrderId != null) break;

            if (attempt < maxAttempts) {
                long sleepMs = backoffMs[Math.min(attempt - 1, backoffMs.length - 1)];
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (exitOrderId == null) {
            log.error("❌ Exit place order failed after {} attempts for trade {}. Response: {}", maxAttempts, persisted.getId(), response);
            try {
                persisted.setStatus(TriggeredTradeStatus.EXIT_FAILED);
                triggeredTradeRepo.save(persisted);
                webSocketSubscriptionService.unsubscribeFromScrip(persisted.getExchange() + persisted.getScripCode());
            } catch (Exception e) {
                log.warn("Failed to persist EXIT_FAILED state after exit placeOrder failures: {}", e.getMessage());
            }

            try {
                String title = "Exit Order Placement Failed after retries ❌";
                StringBuilder body = new StringBuilder();
                body.append("Instrument: ").append(persisted.getSymbol()).append("\n");
                body.append("Exchange: ").append(persisted.getExchange()).append("\n");
                body.append("Attempted Qty: ").append(persisted.getQuantity()).append("\n");
                body.append("Attempted Price: ").append(exitPrice).append("\n");
                body.append("TradeId: ").append(persisted.getId()).append("\n");
                body.append("Note: Exit placeOrder did not return orderId after ").append(maxAttempts).append(" attempts.");
                telegramNotificationService.sendTradeMessageForUser(persisted.getAppUserId(), title, body.toString());
            } catch (Exception e) {
                log.warn("Failed sending telegram notification for exit placeOrder failure: {}", e.getMessage());
            }

            return;
        }

        // Order placed successfully - persist exitOrderId in DB (native update) first
        triggeredTradeRepo.setExitOrderId(persisted.getId(), exitOrderId);

        // Evict caches and clear persistence context so subsequent reads see the native update
        try {
            if (entityManager != null && entityManager.getEntityManagerFactory() != null) {
                entityManager.getEntityManagerFactory().getCache().evict(TriggeredTradeSetupEntity.class, persisted.getId());
                entityManager.clear();
            }
        } catch (Exception ex) {
            log.debug("Failed to evict/clear JPA caches for trade {}: {}", persisted.getId(), ex.getMessage());
        }

        // Now update the in-memory entity and mark status EXIT_ORDER_PLACED (we have an exitOrderId now)
        try {
            persisted.setExitOrderId(exitOrderId);
            persisted.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
            persisted.setExitReason(exitReason);
            triggeredTradeRepo.save(persisted);
        } catch (Exception e) {
            log.debug("Failed to persist exitOrderId/EXIT_ORDER_PLACED for trade {}: {}", persisted.getId(), e.getMessage());
        }


        // If the broker response already indicates the exit order was fully executed and provides a price,
        // we can mark the trade EXITED_SUCCESS immediately to avoid waiting on the poller.
        try {
            if (response.has("data")) {
                JSONObject d = response.getJSONObject("data");
                String respStatus = d.optString("orderStatus", "").toLowerCase();
                String avgPrice = d.optString("avgPrice", "").trim();
                String execPrice = d.optString("execPrice", "").trim();
                String orderPrice = d.optString("orderPrice", "").trim();
                String candidate = !avgPrice.isBlank() ? avgPrice : (!execPrice.isBlank() ? execPrice : (!orderPrice.isBlank() ? orderPrice : null));
                boolean fullyExecuted = respStatus.contains("fully") || respStatus.contains("executed");
                   if (fullyExecuted && candidate != null) {
                    try {
                        double exitPriceVal = Double.parseDouble(candidate);
                        double pnlVal = 0.0d;
                        if (persisted.getEntryPrice() != null && persisted.getQuantity() != null) {
                            pnlVal = java.math.BigDecimal.valueOf(exitPriceVal).subtract(java.math.BigDecimal.valueOf(persisted.getEntryPrice()))
                                    .multiply(java.math.BigDecimal.valueOf(persisted.getQuantity()))
                                    .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                        }
                        int updated = triggeredTradeRepo.markExitedWithPNL(persisted.getId(), TriggeredTradeStatus.EXITED_SUCCESS, exitPriceVal, java.time.LocalDateTime.now(), pnlVal);
                        if (updated == 1) {
                            log.info("✅ placeOrder response indicated fully executed - markExited updated trade {} to EXITED_SUCCESS", persisted.getId());
                            // evict 2nd-level cache and clear persistence context so other threads see the terminal state
                            try {
                                if (entityManager != null && entityManager.getEntityManagerFactory() != null) {
                                    entityManager.getEntityManagerFactory().getCache().evict(TriggeredTradeSetupEntity.class, persisted.getId());
                                    entityManager.clear();
                                }
                            } catch (Exception ignore) {
                                log.debug("Failed to evict/clear JPA cache after markExited for {}: {}", persisted.getId(), ignore.getMessage());
                            }
                            // ensure we unsubscribe and don't leave poller active
                            try { webSocketSubscriptionService.unsubscribeFromScrip(persisted.getExchange() + persisted.getScripCode()); } catch (Exception ignored) {}
                        }
                        return;
                    } catch (Exception ex) {
                        log.debug("Failed to parse candidate exit price from placeOrder response for trade {}: {}", persisted.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error while inspecting placeOrder response for immediate exit persist: {}", e.getMessage());
       }

        // If we didn't mark the trade as EXITED_SUCCESS immediately (below), start the order-status poller to watch exitOrderId
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
                // No transaction active (unlikely) - call directly
                eventPublisher.publishEvent(new OrderPlacedEvent(persisted) );
            }
        } catch (Exception e) {
            log.warn("Failed to schedule order status polling for trade {}: {}", persisted.getId(), e.getMessage());
        }

        orderExitService.performSquareOff(trade, exitPrice, exitReason);
    }


    public void squareOffTrade(Long id) {
        TriggeredTradeSetupEntity tradeSetupEntity = triggeredTradeRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Trade not found"));
        orderExitService.performSquareOff(tradeSetupEntity, ltpCacheService.getLtp(tradeSetupEntity.getScripCode()),
                "Manual Exit");
    }


    public enum TradeStatus {
        FULLY_EXECUTED,
        REJECTED,
        PENDING,
        NO_RECORDS
    }

    public TradeStatus evaluateOrderFinalStatus(TriggeredTradeSetupEntity tradeSetupEntity, JSONObject orderHistoryResponse) {
        Object data = orderHistoryResponse.get("data");

        if (data instanceof String && "no_records".equalsIgnoreCase((String) data)) {
            return TradeStatus.NO_RECORDS;
        }

        JSONArray trades = orderHistoryResponse.getJSONArray("data");

        if (data instanceof String && "no_records".equalsIgnoreCase((String) data)) {
            return TradeStatus.NO_RECORDS;
        }

        Set<String> orderStatusSet = new HashSet<>();
        for (int i = 0; i < trades.length(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            String statusRaw = trade.optString("orderStatus", "").trim();
            String status = statusRaw;
            String normalized = statusRaw.toLowerCase();

            // Normalize known statuses
            if (normalized.contains("fully") || normalized.contains("executed")) {
                status = "Fully Executed";
            } else if (normalized.contains("reject") || normalized.contains("rejected")) {
                status = "Rejected";
            } else if (normalized.contains("pending") || normalized.contains("process")) {
                status = "Pending";
            } else if (normalized.contains("partially")) {
                status = "Pending"; // treat partially executed as pending for now
            }else{
                status = "Pending"; // treat unknown as pending for safety
            }

            // If fully executed, use avgPrice if available else try orderPrice
            if ("Fully Executed".equals(status)) {
                String avgPrice = trade.optString("avgPrice", "").trim();
                String execPrice = trade.optString("execPrice", "").trim();
                String orderPrice = trade.optString("orderPrice", "").trim();
                Double price = null;

                if (!avgPrice.isBlank()) {
                    try { price = Double.parseDouble(avgPrice); } catch (Exception ignored) {}
                }

                if (!execPrice.isBlank()) {
                    try { price = Double.parseDouble(orderPrice); } catch (Exception ignored) {}
                }

                if (price == null && !orderPrice.isBlank()) {
                    try { price = Double.parseDouble(orderPrice); } catch (Exception ignored) {}
                }

                if (price != null) {
                    if (TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(tradeSetupEntity.getStatus())) {
                        tradeSetupEntity.setEntryPrice(price);
                        tradeSetupEntity.setEntryAt(LocalDateTime.now());
                    } else if (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus())) {
                        tradeSetupEntity.setExitPrice(price);
                        tradeSetupEntity.setExitedAt(LocalDateTime.now());
                        double pnl = (price - tradeSetupEntity.getEntryPrice())
                                * tradeSetupEntity.getQuantity();
                        // just keep integer part, positive/negative safe
                        pnl = pnl > 0 ? Math.floor(pnl) : Math.ceil(pnl);
                        tradeSetupEntity.setPnl(pnl);
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

    public List<TriggeredTradeSetupEntity> getRecentExecutions() {
        return triggeredTradeRepo.findTop10ByOrderByIdDesc();
    }

    // New helper to return pending trigger requests for UI/SPA
    public List<org.com.sharekhan.entity.TriggerTradeRequestEntity> getPendingRequests() {
        return triggerTradeRequestRepository.findByStatus(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutionsForUser(Long userId) {
        if (userId == null) return getRecentExecutions();
        return triggeredTradeRepo.findTop10ByAppUserIdOrderByIdDesc(userId);
    }

    public void subscribeForOpenTrades(){
        log.info("🚀 Starting to monitor trades...");
        // 1. Subscribe to LTP for all pending trade requests
        List<TriggerTradeRequestEntity> pendingRequests = triggerTradeRequestRepo
                .findByStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        log.info("📄 Found {} pending trade requests", pendingRequests.size());

        for (TriggerTradeRequestEntity request : pendingRequests) {
            try {
                Integer scripCode = request.getScripCode(); // Assuming you store this or convert symbol to code
                String feedKey = request.getExchange() + scripCode;
                boolean didSub = webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                if (didSub) {
                    log.info("🔁 Subscribed to LTP for scrip {}", scripCode);
                } else {
                    log.debug("Already subscribed to LTP for scrip {} (ref++)", scripCode);
                }
            } catch (Exception e) {
                log.error("❌ Failed to subscribe LTP for trade request {}", request.getId(), e);
            }
        }

        // 2. Subscribe to ACK for all executed trades
        List<TriggeredTradeSetupEntity> executedTrades = triggeredTradeRepo.findByStatus(TriggeredTradeStatus.EXECUTED);

        if(!executedTrades.isEmpty()){
            log.info("📄 Found {} executed trades for ACK monitoring", executedTrades.size());
            for (TriggeredTradeSetupEntity tradeSetupEntity : executedTrades) {
                try {
                    Integer scripCode = tradeSetupEntity.getScripCode(); // Assuming you store this or convert symbol to code
                    String feedKey = tradeSetupEntity.getExchange() + scripCode;
                    boolean didSub = webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                    if (didSub) {
                        log.info("🔁 Subscribed to LTP for executed scrip {}", scripCode);
                    } else {
                        log.debug("Already subscribed to LTP for executed scrip {} (ref++)", scripCode);
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to subscribe LTP for trade request {}", tradeSetupEntity.getId(), e);
                }
            }
            // subscribe to ACK for this customer's feed
            Long custId = null;
            if (executedTrades.get(0).getBrokerCredentialsId() != null) {
                custId = brokerCredentialsRepository.findById(executedTrades.get(0).getBrokerCredentialsId())
                        .map(BrokerCredentialsEntity::getCustomerId).orElse(null);
            }
            webSocketSubscriptionService.subscribeToAck(String.valueOf(custId != null ? custId : TokenLoginAutomationService.customerId));
        }

        log.info("✅ Monitoring setup complete.");

    }



    /**
     * Admin helper: execute a saved TriggerTradeRequestEntity (re-use existing execute flow)
     */
    public TriggeredTradeSetupEntity executeTradeFromEntity(TriggerTradeRequestEntity requestEntity) {
        // If the saved request is missing broker credentials, resolve a sensible default
        try {
            if (requestEntity.getBrokerCredentialsId() == null) {
                Long resolved = null;
                Long uid = requestEntity.getAppUserId();
                if (uid != null) {
                    // Prefer active Sharekhan credentials for this AppUser, else any Sharekhan for the user
                    List<BrokerCredentialsEntity> list = brokerCredentialsRepository.findByAppUserId(uid);
                    if (list != null && !list.isEmpty()) {
                        BrokerCredentialsEntity chosen = null;
                        for (BrokerCredentialsEntity b : list) {
                            if (b == null) continue;
                            if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")
                                    && Boolean.TRUE.equals(b.getActive())) { chosen = b; break; }
                        }
                        if (chosen == null) {
                            for (BrokerCredentialsEntity b : list) {
                                if (b == null) continue;
                                if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")) { chosen = b; break; }
                            }
                        }
                        if (chosen != null) {
                            resolved = chosen.getId();
                        }
                    }
                }
                // As a final fallback, if still null, try any active Sharekhan credentials globally (admin/dev environments)
                if (resolved == null) {
                    try {
                        List<BrokerCredentialsEntity> all = brokerCredentialsRepository.findAll();
                        BrokerCredentialsEntity chosen = null;
                        for (BrokerCredentialsEntity b : all) {
                            if (b == null) continue;
                            if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")
                                    && Boolean.TRUE.equals(b.getActive())) { chosen = b; break; }
                        }
                        if (chosen == null) {
                            for (BrokerCredentialsEntity b : all) {
                                if (b == null) continue;
                                if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")) { chosen = b; break; }
                            }
                        }
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
        Double ltp = ltpCacheService.getLtp(requestEntity.getScripCode());
        if (ltp == null) {
            // if LTP not available, fall back to entry price
            ltp = requestEntity.getEntryPrice();
        }

        // build a temporary TriggeredTradeSetupEntity from the saved request so we can reuse the execute(...) method
        TriggeredTradeSetupEntity temp = new TriggeredTradeSetupEntity();
        temp.setScripCode(requestEntity.getScripCode());
        temp.setBrokerCredentialsId(requestEntity.getBrokerCredentialsId());
        temp.setAppUserId(requestEntity.getAppUserId());
        temp.setExchange(requestEntity.getExchange());
        temp.setSymbol(requestEntity.getSymbol());
        // Quantity in TriggeredTradeSetupEntity is Long; copy directly from request
        if (requestEntity.getQuantity() != null) {
            temp.setQuantity(requestEntity.getQuantity());
        }
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

        // run execution using the converted entity
        return execute(temp, ltp);
    }
}
