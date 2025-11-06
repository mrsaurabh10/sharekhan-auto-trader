package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import com.sharekhan.model.OrderParams;
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
import org.com.sharekhan.ws.WebSocketConnector;
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
    @Lazy
    @Autowired
    private final WebSocketClientService webSocketClientService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketConnector webSocketConnector;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final TokenStoreService   tokenStoreService;
    @Autowired
    private LtpCacheService ltpCacheService;

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
                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
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
                            double rawPnl = (trade.getExitPrice() != null ? trade.getExitPrice() : exitPrice) - trade.getEntryPrice();
                            rawPnl = rawPnl * trade.getQuantity();
                            trade.setPnl(Math.round(rawPnl * 100.0) / 100.0);
                        }
                        trade.setExitedAt(java.time.LocalDateTime.now());
                    } catch (Exception e) {
                        log.warn("⚠️ Failed computing PnL/exit price for trade {}: {}", trade.getId(), e.getMessage());
                    }

                    tradeRepo.save(trade);

                    ScheduledFuture<?> future = activePolls.remove(orderIdToMonitor);
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
                    ScheduledFuture<?> future = activePolls.remove(orderIdToMonitor);
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}",orderIdToMonitor);
                    }
                    String feedKey = trade.getExchange() + trade.getScripCode();
                    webSocketSubscriptionHelper.unsubscribeFromScrip(feedKey);
                    return;
                }else if(TradeStatus.PENDING.equals(tradeStatus)){
                    //need to write the logic of modification of order
                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
                        Double ltp = ltpCacheService.getLtp(trade.getScripCode());
                        if(ltp > trade.getEntryPrice()){
                            ShareKhanOrderUtil.modifyOrder(sharekhanConnect,trade,ltp);
                        }
                    }else{
                        Double ltp = ltpCacheService.getLtp(trade.getScripCode());
                        if(ltp > trade.getEntryPrice()){
                            ShareKhanOrderUtil.modifyOrder(sharekhanConnect,trade,ltp);
                        }
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

