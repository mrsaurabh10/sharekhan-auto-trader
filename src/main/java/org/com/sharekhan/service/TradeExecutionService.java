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


    public TriggerTradeRequestEntity executeTrade(TriggerRequest request) {

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
            int maxAmtPerTrade = Integer.parseInt(userConfigService.getConfig("saurabh", "max_amount_per_trade", "25000"));
            int maxLossPerTrade = Integer.parseInt(userConfigService.getConfig("saurabh", "max_loss_per_trade", "8000"));

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

        int lotSize = script.getLotSize() != null ? script.getLotSize() : 1;
        long finalQuantity;
        if (isNoStrikeExchange) {
            // For NC/BC quantity is per-share (no lot multiplication)
            finalQuantity = request.getQuantity() != null ? request.getQuantity().longValue() : 0L;
        } else {
            finalQuantity = (long) request.getQuantity() * lotSize;
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

    public void execute(TriggeredTradeSetupEntity trigger, double ltp) {
        try {
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
            order.quantity = Long.valueOf(trigger.getQuantity());
            order.price =  String.valueOf(ltp); //choosing ltp as higher chances of execution
            order.orderType = "NORMAL";
            order.productType = "INVESTMENT";
            order.instrumentType = trigger.getInstrumentType(); //FUTCUR, FS, FI, OI, OS, FUTCURR, OPTCURR
            order.strikePrice =  String.valueOf(trigger.getStrikePrice());
            order.optionType = trigger.getOptionType();
            order.expiry = trigger.getExpiry();
            order.requestType = "NEW";
            order.afterHour =  "N";
            order.validity = "GFD";
            order.rmsCode ="ANY";
            order.disclosedQty = 0L;
            order.channelUser = clientCode ;//TokenLoginAutomationService.clientCode;

            var response = sharekhanConnect.placeOrder(order);

            log.info("Response received " + response);
            if (response == null) {
                log.error("❌ Sharekhan order failed or returned null for trigger {}", trigger.getId());
            }else if (!response.has("data")){
                log.error("❌ Sharekhan order failed or returned null for trigger {}" + response, trigger.getId());
            }else if (response.getJSONObject("data").has("orderId")){
                String orderId = response.getJSONObject("data").getString("orderId");
                //order placed  successfully
                log.info("✅ Sharekhan order placed successfully: {}", response.toString(2));

                // check the status with a delay of .5 second
                //Thread.sleep(500);

                TriggeredTradeSetupEntity triggeredTradeSetupEntity = new TriggeredTradeSetupEntity();
                triggeredTradeSetupEntity.setOrderId(orderId);

                triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);

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

                eventPublisher.publishEvent(new OrderPlacedEvent(triggeredTradeSetupEntity));

                log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trigger.getScripCode(), ltp);
            }

        } catch (Exception e) {
            log.error("❌ Error executing trade for trigger {}: {}", trigger.getId(), e.getMessage(), e);
        }
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
    }

    public boolean forceCloseByScripCode(int scripCode) {
        Optional<TriggeredTradeSetupEntity> tradeOpt = triggeredTradeRepo
                .findByScripCodeAndStatus(scripCode, TriggeredTradeStatus.EXECUTED);

        if (tradeOpt.isPresent()) {
            TriggeredTradeSetupEntity trade = tradeOpt.get();

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
            String status = trade.optString("orderStatus", "").trim();
            if("Fully Executed".equals(status)) {
                String avgPrice = trade.optString("avgPrice", "").trim();
                if(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(tradeSetupEntity.getStatus()) ) {
                    tradeSetupEntity.setEntryPrice(Double.parseDouble(avgPrice));
                }else if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(tradeSetupEntity.getStatus()) ) {
                    tradeSetupEntity.setExitPrice(Double.parseDouble(avgPrice));
                }
            }
            orderStatusSet.add(status);
        }

        if(orderStatusSet.contains("Fully Executed")){
            return TradeStatus.FULLY_EXECUTED;
        }

        if(orderStatusSet.contains("Pending")){
            return TradeStatus.PENDING;
        }

        if(orderStatusSet.contains("Rejected")){
            return TradeStatus.REJECTED;
        }

        // Still in progress or partially executed
        log.info("⏳ Order is still pending or partially executed.");
        return TradeStatus.NO_RECORDS;
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutions() {
        return triggeredTradeRepo.findTop10ByOrderByIdDesc();
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
                webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                log.info("🔁 Subscribed to LTP for scrip {}", scripCode);
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
                    webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                    log.info("🔁 Subscribed to LTP for executed scrip {}", scripCode);
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
    public void executeTradeFromEntity(TriggerTradeRequestEntity requestEntity) {
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
        // TriggerTradeRequestEntity.quantity is final quantity (long) — TriggeredTradeSetupEntity.quantity is Integer
        if (requestEntity.getQuantity() != null) {
            long q = requestEntity.getQuantity();
            if (q > Integer.MAX_VALUE) {
                temp.setQuantity(Integer.MAX_VALUE);
            } else if (q < Integer.MIN_VALUE) {
                temp.setQuantity(Integer.MIN_VALUE);
            } else {
                temp.setQuantity((int) q);
            }
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
        execute(temp, ltp);
    }
}
