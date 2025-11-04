package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.model.OrderParams;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExitService {

    private final TokenStoreService tokenStoreService;
    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final WebSocketSubscriptionService webSocketSubscriptionService;

    @Transactional
    public void performSquareOff(TriggeredTradeSetupEntity trade, double exitPrice, String exitReason) {
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

        // Prefer a per-customer token when placing the exit order
        String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, trade.getCustomerId());
        if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
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
            // update status to reflect that an exit order was placed
            trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
            trade.setExitReason(exitReason);
            trade.setExitOrderId(exitOrderId);
            triggeredTradeRepo.save(trade);
            //subscribe to ack feed
            try {
                if (trade.getCustomerId() != null) {
                    webSocketSubscriptionService.subscribeToAck(String.valueOf(trade.getCustomerId()));
                } else {
                    webSocketSubscriptionService.subscribeToAck(String.valueOf(TokenLoginAutomationService.customerId));
                }
            } catch (Exception e) {
                log.warn("Failed to subscribe ACK for customer {}: {}", trade.getCustomerId(), e.getMessage());
            }

            //monitor trade - publish event not available here; the caller can handle if needed

            log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trade.getScripCode(), exitPrice);
        }

        log.info("✅ Trade exited [{}]: PnL = {}", exitReason, trade.getPnl());
    }
}

