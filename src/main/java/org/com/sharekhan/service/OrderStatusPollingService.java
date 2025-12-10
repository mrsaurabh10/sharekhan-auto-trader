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
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final TokenStoreService   tokenStoreService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    @Autowired
    private LtpCacheService ltpCacheService;

    public void monitorOrderStatus(TriggeredTradeSetupEntity trade) {
        Runnable pollTask = () -> {
            String orderIdToMonitor = TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus()) ? trade.getExitOrderId() : trade.getOrderId();
            try {

                // Prefer a customer-specific token if available
                Long custId = null;
                if (trade.getBrokerCredentialsId() != null) {
                    custId = brokerCredentialsRepository.findById(trade.getBrokerCredentialsId()).map(BrokerCredentialsEntity::getCustomerId).orElse(null);
                }
                String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, custId); // ✅ fetch fresh token per-customer
                if (accessToken == null) {
                    accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
                }
                SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);

                JSONObject response = sharekhanConnect.orderHistory(trade.getExchange(), custId,
                        orderIdToMonitor);
                TradeStatus tradeStatus = tradeExecutionService.evaluateOrderFinalStatus(trade,response);
                if(TradeStatus.FULLY_EXECUTED.equals(tradeStatus)) {
                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
                        webSocketSubscriptionHelper.unsubscribeFromScrip(trade.getExchange() + trade.getScripCode());
                    }else if(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXECUTED);
                    }
                    tradeRepo.save(trade);
                    //todo add executed price here
                    ScheduledFuture<?> future = activePolls.remove(orderIdToMonitor);
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}", orderIdToMonitor);
                    }
                    return;
                }else if (TradeStatus.REJECTED.equals(tradeStatus)) {

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
                // SDK-level error (HTTP/Sharekhan error shape). Treat as transient and keep polling.
                log.warn("❌ Error polling order status for {}: {}", orderIdToMonitor, e.getMessage());
            } catch (NullPointerException npe) {
                // Defensive guard: upstream SDK sometimes assumes a non-null Content-Type header and NPEs
                // Make this non-fatal so our scheduled task keeps running until the endpoint stabilizes
                log.warn("⚠️ Transient NPE from SDK while polling order {} — likely missing Content-Type on response; will retry", orderIdToMonitor, npe);
            } catch (Exception e) {
                // Do NOT rethrow — uncaught exceptions cancel ScheduledFuture. Log and continue so we keep polling.
                log.warn("⚠️ Unexpected error while polling order {}: {} — continuing", orderIdToMonitor, e.toString(), e);
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
