package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.model.OrderParams;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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

    public void execute(TriggerTradeRequestEntity trigger, double ltp) {
        try {
            String accessToken = tokenStoreService.getAccessToken(); // ✅ fetch fresh token

            SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);

            // 🧾 Build order request
            OrderParams order = new OrderParams();
            order.customerId = TokenLoginAutomationService.customerId; // You may later fetch this from the user/session
            order.scripCode =  trigger.getScripCode();
            order.tradingSymbol = trigger.getSymbol();
            order.exchange = trigger.getExchange();
            order.transactionType = "B";
            order.quantity = trigger.getQuantity();
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
            order.channelUser = TokenLoginAutomationService.clientCode;

            var response = sharekhanConnect.placeOrder(order);

            log.info("Response received " + response);
            if (response == null ) {
                log.error("❌ Sharekhan order failed or returned null for trigger {}", trigger.getId());
                return;
            }else if (!response.has("data")){
                log.error("❌ Sharekhan order failed or returned null for trigger {}" + response, trigger.getId());
                return;
            }else if (response.getJSONObject("data").has("orderId")){
                String orderId = response.getJSONObject("data").getString("orderId");
                //order placed  successfully
                log.info("✅ Sharekhan order placed successfully: {}", response.toString(2));

                // check the status with a delay of .5 second
                //Thread.sleep(500);

                //JSONObject orderHistory =  sharekhanConnect.getTrades(trigger.getExchange(), TokenLoginAutomationService.customerId,orderId);


                //TradeStatus tradeStatus = evaluateOrderFinalStatus(triggeredTradeSetupEntity,orderHistory);



                //trigger.setStatus(TriggeredTradeStatus.TRIGGERED);

                //since the order is triggered then place the entity in the setup

                TriggeredTradeSetupEntity triggeredTradeSetupEntity = new TriggeredTradeSetupEntity();
                triggeredTradeSetupEntity.setOrderId(orderId);

                //if(!tradeStatus.equals(TradeStatus.FULLY_EXECUTED)){
                triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
                //} else{
                  //  triggeredTradeSetupEntity.setStatus(TriggeredTradeStatus.EXECUTED);
                //}

                triggeredTradeSetupEntity.setScripCode(trigger.getScripCode());
                triggeredTradeSetupEntity.setExchange(trigger.getExchange());
                triggeredTradeSetupEntity.setCustomerId(TokenLoginAutomationService.customerId);
                triggeredTradeSetupEntity.setSymbol(trigger.getSymbol());
                triggeredTradeSetupEntity.setExpiry(trigger.getExpiry());
                triggeredTradeSetupEntity.setStrikePrice(trigger.getStrikePrice());
                triggeredTradeSetupEntity.setStopLoss(trigger.getStopLoss());
                triggeredTradeSetupEntity.setTarget1(trigger.getTarget1());
                triggeredTradeSetupEntity.setTarget2(trigger.getTarget2());
                triggeredTradeSetupEntity.setQuantity(trigger.getQuantity().intValue());
                triggeredTradeSetupEntity.setTarget3(trigger.getTarget3());
                triggeredTradeSetupEntity.setInstrumentType(trigger.getInstrumentType());
                triggeredTradeSetupEntity.setEntryPrice(ltp);
                triggeredTradeSetupEntity.setOptionType(trigger.getOptionType());
                triggeredTradeSetupEntity.setIntraday(trigger.getIntraday());
                triggeredTradeRepo.save(triggeredTradeSetupEntity);

                eventPublisher.publishEvent(new OrderPlacedEvent(triggeredTradeSetupEntity));

                //dont need the unsubscribe code here as we will not unsubscribe
                // reason being need to take care of pending order
               // String feedKey = trigger.getExchange() + trigger.getScripCode();
                //webSocketSubscriptionService.unsubscribeFromScrip(feedKey);


                log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trigger.getScripCode(), ltp);
            }
            // no change in the status
            // need to handle failure cases

        } catch (Exception e) {
            log.error("❌ Error executing trade for trigger {}: {}", trigger.getId(), e.getMessage(), e);
        }
    }

    public void markOrderExecuted(String orderId) {
        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Trade not found"));

        trade.setStatus(TriggeredTradeStatus.EXECUTED);
        triggeredTradeRepo.save(trade);

        // ✅ Start LTP monitoring
        String feedKey = trade.getExchange() + trade.getScripCode();
        webSocketSubscriptionService.subscribeToScrip(feedKey);

        log.info("✅ Order executed, monitoring LTP for SL/target: {}", feedKey);
    }

    public void markOrderExited(String orderId) {
        TriggeredTradeSetupEntity trade = triggeredTradeRepo.findByExitOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Trade not found"));

        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        //trade.setExitPrice(Double.parseDouble(data.get("TradePrice").asText()));
        trade.setPnl((trade.getExitPrice() - trade.getEntryPrice()) * trade.getQuantity());
        triggeredTradeRepo.save(trade);

        // ✅ Start LTP monitoring
        webSocketSubscriptionService.unsubscribeFromScrip(trade.getExchange() + trade.getScripCode());

        log.info("✅ Order executed, monitoring LTP for SL/target: {}", trade.getExchange() + trade.getScripCode());

    }

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

            squareOff(trade, exitPrice,"Force fully close the trade"); // reuse existing logic
            return true;
        } else {
            return false;
        }
    }

    @Transactional
    public void squareOff(TriggeredTradeSetupEntity trade, double exitPrice, String exitReason) {
        // Place opposite order (SELL for buy-side trades)
        OrderParams exitOrder = new OrderParams();
        exitOrder.customerId =     trade.getCustomerId();
        exitOrder.exchange = trade.getExchange();
        exitOrder.scripCode = trade.getScripCode();
        exitOrder.strikePrice = String.valueOf(trade.getStrikePrice());
        exitOrder.optionType = trade.getOptionType();
        exitOrder.tradingSymbol = trade.getSymbol();
        exitOrder.quantity = trade.getQuantity().longValue();
        exitOrder.instrumentType = trade.getInstrumentType();
        exitOrder.productType = "INVESTMENT";
        exitOrder.price = String.valueOf(exitPrice); //0.0 means Market Price
        exitOrder.transactionType = "S"; // Assuming original was "B"
        exitOrder.orderType = "NORMAL";
        exitOrder.expiry = trade.getExpiry();
        exitOrder.requestType = "NEW";
        exitOrder.afterHour =  "N";
        exitOrder.validity = "GFD";
        exitOrder.rmsCode = "ANY";
        exitOrder.disclosedQty = 0L;
        exitOrder.channelUser = TokenLoginAutomationService.clientCode;

        String accessToken = tokenStoreService.getAccessToken(); // ✅ fetch fresh token
        SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);

        JSONObject response = sharekhanConnect.placeOrder(exitOrder);

        if (response == null ) {
            log.error("❌ Sharekhan order failed or returned null for trigger {}", trade.getScripCode());
            return;
        }else if (!response.has("data")){
            log.error("❌ Sharekhan order failed or returned null for trigger {}" + response, trade.getScripCode());
            return;
        }else if (response.getJSONObject("data").getString("orderId") != null){
            String exitOrderId = response.getJSONObject("data").getString("orderId");
            //order placed  successfully
            log.info("✅ Sharekhan order placed successfully: {}", response.toString(2));


            trade.setExitOrderId(exitOrderId);
            trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED); // ✅ New status
            trade.setExitPrice(exitPrice);
            trade.setExitReason(exitReason);
            trade.setExitOrderId(exitOrderId);
            trade.setPnl((exitPrice - trade.getEntryPrice()) * trade.getQuantity()); // adjust for B/S
            triggeredTradeRepo.save(trade);
            //subscribe to ack feed
            // 🔌 Subscribe to ACK
            webSocketSubscriptionService.subscribeToAck(String.valueOf(TokenLoginAutomationService.customerId));
            //String feedKey = trade.getExchange() + trade.getScripCode();
            //webSocketSubscriptionService.unsubscribeFromScrip(feedKey);

            //monitor trade
            eventPublisher.publishEvent(new OrderPlacedEvent(trade));

            log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trade.getScripCode(), exitPrice);
        }



        log.info("✅ Trade exited [{}]: PnL = {}", exitReason, trade.getPnl());
    }

    public void squareOffTrade(Long id) {
        TriggeredTradeSetupEntity tradeSetupEntity = triggeredTradeRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Trade not found"));
        this.squareOff(tradeSetupEntity,ltpCacheService.getLtp(tradeSetupEntity.getScripCode()),
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
                String orderPrice = trade.optString("orderPrice", "").trim();
                tradeSetupEntity.setEntryPrice(Double.parseDouble(orderPrice));
            }
            orderStatusSet.add(status);
//
//            switch (status) {
//                case "Rejected":
//                    String reason = trade.optString("clientGroup", "Unknown");
//                    log.warn("❌ Order Rejected - Reason: {}", reason);
//                    return TradeStatus.REJECTED;
//
//                case "Fully Executed":
//                    log.info("✅ Order Fully Executed - Order ID: {}", trade.optString("orderId"));
//                    String orderPrice = trade.optString("orderPrice", "").trim();
//                    tradeSetupEntity.setEntryPrice(Double.parseDouble(orderPrice));
//                    return TradeStatus.FULLY_EXECUTED;
//            }
        }

        if(orderStatusSet.contains("Fully Executed")){
            return TradeStatus.FULLY_EXECUTED;
        }

        if(orderStatusSet.contains("Pending")){
            return TradeStatus.PENDING;
        }

        // Still in progress or partially executed
        log.info("⏳ Order is still pending or partially executed.");
        return TradeStatus.NO_RECORDS;
    }

    public List<TriggeredTradeSetupEntity> getRecentExecutions() {
        return triggeredTradeRepo.findTop10ByOrderByIdDesc();
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
            webSocketSubscriptionHelper.subscribeToAck(String.valueOf(TokenLoginAutomationService.customerId));
        }

        log.info("✅ Monitoring setup complete.");

    }



}
