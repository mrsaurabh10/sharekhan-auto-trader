package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

import static org.com.sharekhan.service.TradeExecutionService.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusPollingService {

     private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
     private final TriggeredTradeSetupRepository tradeRepo;
     private final Map<String, ScheduledFuture<?>> activePolls = new ConcurrentHashMap<>();
     // Track last modify price attempted per orderId to avoid spamming identical modify requests
     private final Map<String, Double> lastModifyPrice = new ConcurrentHashMap<>();
     @Lazy
     @Autowired
     private final WebSocketClientService webSocketClientService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final TokenStoreService   tokenStoreService;
    @Autowired
    private LtpCacheService ltpCacheService;

    @Autowired
    private TelegramNotificationService telegramNotificationService;

    public void monitorOrderStatus(TriggeredTradeSetupEntity trade) {
        Runnable pollTask = () -> {
            String orderIdToMonitor = TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus()) ? trade.getExitOrderId() : trade.getOrderId();
            try {

                String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN); // ✅ fetch fresh token
                SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);

                JSONObject response = sharekhanConnect.orderHistory(trade.getExchange(), trade.getCustomerId(), orderIdToMonitor);

                // Evaluate final status and log a very compact summary (avoid dumping full JSON)
                TradeStatus tradeStatus = tradeExecutionService.evaluateOrderFinalStatus(trade, response);
                try {
                    int records = 0;
                    String lastRecStatus = "";
                    String lastRecAvg = "";
                    String lastRecExecQty = "";
                    if (response != null && response.has("data") && response.get("data") instanceof JSONArray) {
                        JSONArray arr = response.getJSONArray("data");
                        records = arr.length();
                        if (records > 0) {
                            JSONObject last = arr.getJSONObject(records - 1);
                            lastRecStatus = last.optString("orderStatus", "");
                            lastRecAvg = last.optString("avgPrice", last.optString("orderPrice", ""));
                            lastRecExecQty = last.has("execQty") ? String.valueOf(last.optInt("execQty")) : last.optString("execQty", "");
                        }
                    }
                    log.info("OrderHistory: orderId={} computedStatus={} records={} lastStatus={} avg={} execQty={}",
                            orderIdToMonitor, tradeStatus, records, lastRecStatus, lastRecAvg, lastRecExecQty);
                } catch (Exception e) {
                    log.warn("Failed to build order history compact log: {}", e.getMessage());
                }
                if(TradeStatus.FULLY_EXECUTED.equals(tradeStatus)) {
                    // Determine if this was an exit order or entry order
                    boolean wasExitOrder = TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus());
                    if(wasExitOrder) {
                        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
                        webSocketSubscriptionHelper.unsubscribeFromScrip(trade.getExchange() + trade.getScripCode());
                    } else if(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXECUTED);
                    }

                    // Ensure exitPrice/entryPrice set by evaluateOrderFinalStatus are persisted; compute PnL now if missing
                    try {
                        Double exitPrice = trade.getExitPrice();
                        if (exitPrice == null) {
                            // fallback to LTP cache
                            exitPrice = ltpCacheService.getLtp(trade.getScripCode());
                            if (exitPrice != null) trade.setExitPrice(exitPrice);
                        }
                        if (trade.getPnl() == null && trade.getEntryPrice() != null && trade.getQuantity() != null && trade.getQuantity() > 0) {
                            // Determine effective exit price safely
                            Double effExit = trade.getExitPrice() != null ? trade.getExitPrice() : exitPrice;
                            if (effExit == null) {
                                // fallback to entryPrice (zero pnl) to avoid NPE
                                effExit = trade.getEntryPrice();
                            }
                            try {
                                java.math.BigDecimal exitBd = java.math.BigDecimal.valueOf(effExit);
                                java.math.BigDecimal entryBd = java.math.BigDecimal.valueOf(trade.getEntryPrice());
                                long qty = trade.getQuantity() == null ? 0L : trade.getQuantity();
                                java.math.BigDecimal qtyBd = java.math.BigDecimal.valueOf(qty);
                                java.math.BigDecimal rawPnlBd = exitBd.subtract(entryBd).multiply(qtyBd);
                                double rawPnl = rawPnlBd.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                                log.debug("Computed PnL for trade {}: entry={} exit={} qty={} rawPnl={}", trade.getId(), trade.getEntryPrice(), effExit, qty, rawPnl);
                                trade.setPnl(rawPnl);
                            } catch (Exception ex) {
                                log.warn("Failed computing PnL precisely for trade {}: {}", trade.getId(), ex.getMessage());
                            }
                        }
                        trade.setExitedAt(java.time.LocalDateTime.now());
                    } catch (Exception e) {
                        log.warn("⚠️ Failed computing PnL/exit price for trade {}: {}", trade.getId(), e.getMessage());
                    }

                    tradeRepo.save(trade);

                    // Send telegram notification for executed/exit
                    try {
                        String title = wasExitOrder ? "Trade Exited ✅" : "Order Executed ✅";
                        StringBuilder body = new StringBuilder();
                        body.append("Instrument: ").append(trade.getSymbol());
                        if (trade.getStrikePrice() != null) body.append(" ").append(trade.getStrikePrice());
                        if (trade.getOptionType() != null) body.append(" ").append(trade.getOptionType());
                        body.append("\nOrderId: ").append(orderIdToMonitor);
                        body.append("\nStatus: ").append(trade.getStatus());
                        if (trade.getEntryPrice() != null) body.append("\nEntry: ").append(trade.getEntryPrice());
                        if (trade.getExitPrice() != null) body.append("\nExit: ").append(trade.getExitPrice());
                        if (trade.getPnl() != null) body.append("\nPnL: ").append(trade.getPnl());
                        telegramNotificationService.sendTradeMessage(title, body.toString());
                    } catch (Exception e) {
                        log.warn("Failed sending telegram notification for execution: {}", e.getMessage());
                    }

                    ScheduledFuture<?> future = activePolls.remove(orderIdToMonitor);
                    // cleanup last modify price cache
                    lastModifyPrice.remove(orderIdToMonitor);
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}", orderIdToMonitor);
                    }
                    return;
                } else if (TradeStatus.REJECTED.equals(tradeStatus)) {

                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXIT_FAILED);
                    }else{
                        trade.setStatus(TriggeredTradeStatus.REJECTED);
                    }


                    tradeRepo.save(trade);

                    // Send telegram for rejection
                    try {
                        String title = "Order Rejected ❌";
                        StringBuilder body = new StringBuilder();
                        body.append("Instrument: ").append(trade.getSymbol());
                        if (trade.getStrikePrice() != null) body.append(" ").append(trade.getStrikePrice());
                        if (trade.getOptionType() != null) body.append(" ").append(trade.getOptionType());
                        body.append("\nOrderId: ").append(orderIdToMonitor);
                        body.append("\nStatus: ").append(trade.getStatus());
                        if (trade.getExitReason() != null) body.append("\nReason: ").append(trade.getExitReason());
                        telegramNotificationService.sendTradeMessage(title, body.toString());
                    } catch (Exception e) {
                        log.warn("Failed sending telegram notification for rejection: {}", e.getMessage());
                    }

                    ScheduledFuture<?> future = activePolls.remove(orderIdToMonitor);
                    lastModifyPrice.remove(orderIdToMonitor);
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}",orderIdToMonitor);
                    }
                    String feedKey = trade.getExchange() + trade.getScripCode();
                    webSocketSubscriptionHelper.unsubscribeFromScrip(feedKey);
                    return;
                } else if (TradeStatus.PENDING.equals(tradeStatus)) {
                    // Try to improve chances of execution by modifying the pending order to current LTP
                    try {
                        // Reload persisted trade to ensure we have latest orderId/exitOrderId/quantity/status
                        TriggeredTradeSetupEntity persisted = tradeRepo.findById(trade.getId()).orElse(trade);
                        Double ltp = ltpCacheService.getLtp(persisted.getScripCode());
                        Double candidatePrice = ltp;
                        boolean usedFallback = false;

                        // If we don't have LTP, try to extract avgPrice/orderPrice from the orderHistory response as a fallback
                        if (candidatePrice == null) {
                            try {
                                if (response != null && response.has("data") && response.get("data") instanceof JSONArray) {
                                    JSONArray arr = response.getJSONArray("data");
                                    if (!arr.isEmpty()) {
                                        JSONObject last = arr.getJSONObject(arr.length() - 1);
                                        String avg = last.optString("avgPrice", "").trim();
                                        String ordp = last.optString("orderPrice", "").trim();
                                        String val = !avg.isBlank() ? avg : (!ordp.isBlank() ? ordp : null);
                                        if (val != null) {
                                            try {
                                                candidatePrice = Double.parseDouble(val);
                                                usedFallback = true;
                                                log.debug("Using orderHistory fallback price {} for order {}", candidatePrice, orderIdToMonitor);
                                            } catch (Exception ignore) {
                                                // ignore parse error, candidatePrice remains null
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                log.debug("Failed to extract fallback price from orderHistory for {}: {}", orderIdToMonitor, ex.getMessage());
                            }
                        }

                        if (candidatePrice == null) {
                            log.debug("No LTP or fallback price available to consider modifying pending order {}", orderIdToMonitor);
                        } else {
                            // Choose a sensible reference price: for entry orders use entryPrice; for exit orders use exitPrice if available
                            Double refPrice = trade.getEntryPrice();
                            if (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus()) && trade.getExitPrice() != null) {
                                refPrice = trade.getExitPrice();
                            }

                            double ref = refPrice != null ? refPrice : candidatePrice;
                            double diff = Math.abs(candidatePrice - ref);

                            // Threshold: modify only if price moved by at least 0.5 INR or 0.2% (whichever is larger)
                            double pctThreshold = Math.max(0.002 * (ref > 0 ? ref : candidatePrice), 0.5);

                            // don't re-send the same modify if we've already attempted the same price recently
                            Double lastAttempt = lastModifyPrice.get(orderIdToMonitor);
                            boolean priceChangedSinceLastAttempt = lastAttempt == null || Math.abs(candidatePrice - lastAttempt) > 1e-6;

                            if (diff >= pctThreshold && priceChangedSinceLastAttempt) {
                                log.info("Pending order {} for trade {}: attempting MODIFY -> price={} (ltpUsed={} fallback={}) refPrice={} diff={} threshold={}", orderIdToMonitor, persisted.getId(), candidatePrice, ltp != null, usedFallback, ref, diff, pctThreshold);
                                try {
                                    JSONObject modResp = ShareKhanOrderUtil.modifyOrder(sharekhanConnect, persisted, candidatePrice);
                                    if (modResp != null) {
                                        String msg = modResp.has("message") ? String.valueOf(modResp.opt("message")) : modResp.toString();
                                        log.info("ModifyOrder response for order {}: {}", orderIdToMonitor, msg);
                                        // record last attempted modify price on success
                                        lastModifyPrice.put(orderIdToMonitor, candidatePrice);
                                    } else {
                                        log.info("ModifyOrder call returned null for order {}", orderIdToMonitor);
                                        // still record attempt to avoid tight retry loops
                                        lastModifyPrice.put(orderIdToMonitor, candidatePrice);
                                    }
                                } catch (Exception ex) {
                                    log.error("Failed to modify pending order {} for trade {}: {}", orderIdToMonitor, trade.getId(), ex.getMessage(), ex);
                                }
                            } else {
                                if (!priceChangedSinceLastAttempt) {
                                    log.debug("Skipping modify for order {} because price equals last attempted {}", orderIdToMonitor, lastAttempt);
                                } else {
                                    log.debug("No modify required for pending order {} (price={}, ref={}, diff={}, threshold={})", orderIdToMonitor, candidatePrice, ref, diff, pctThreshold);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error while attempting modify for pending order {}: {}", orderIdToMonitor, e.getMessage(), e);
                    }
                }

                log.info("⌛ Order still pending: {}",orderIdToMonitor);
            } catch (SharekhanAPIException e) {
                log.error("❌ Error polling order status for {}: {}", orderIdToMonitor, e.getMessage());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        // Poll every 0.5 seconds, up to 2 minutes
        String orderIdToMonitor = TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus()) ? trade.getExitOrderId() : trade.getOrderId();
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(pollTask, 0, 500, TimeUnit.MILLISECONDS);
        activePolls.put(orderIdToMonitor, future);

        // Optional: Cancel polling after 2 minutes
//        executor.schedule(() -> {
//            log.warn("⚠️ Stopping polling after timeout for order {}", orderIdToMonitor);
//        }, 2, TimeUnit.MINUTES);
    }



    /*
    Example Response from Sharekhan

    https://api.sharekhan.com/skapi/services/reports/NF/73196/136757437
{
    "data": [
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "0",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "O",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:40.0",
            "avgPrice": "0",
            "orsOrderId": 0,
            "orderStatus": "In-Process",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "BOG",
            "exchAckDateTime": "",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2951621",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:40.0",
            "avgPrice": "0",
            "orsOrderId": 136757437,
            "orderStatus": "In-Process",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "BOG",
            "exchAckDateTime": "",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2951621",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "0",
            "orsOrderId": 1446122681227650728,
            "orderStatus": "Pending",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_NOR",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2857079",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 150,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 1050,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "23.45",
            "orsOrderId": 1446122681227656516,
            "orderStatus": "Partly Executed",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_TC",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2843573",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 0,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 1200,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "23.45",
            "orsOrderId": 1446122681227661860,
            "orderStatus": "Fully Executed",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_TC",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        }
    ],
    "message": "order_history",
    "status": 200,
    "timestamp": "2025-10-28T14:52:32+05:30"
}



     */
}
