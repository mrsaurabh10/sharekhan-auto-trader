package org.com.sharekhan.service.broker;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.model.OrderParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharekhanBrokerService implements BrokerService {

    private final TokenStoreService tokenStoreService;

    @Override
    public Broker getBroker() {
        return Broker.SHAREKHAN;
    }

    @Override
    public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp) {
        return executeSharekhanOrder(trade, context, ltp, "B", "NEW");
    }

    @Override
    public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice) {
        return executeSharekhanOrder(trade, context, exitPrice, "S", "NEW");
    }

    private OrderPlacementResult executeSharekhanOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double price, String transactionType, String requestType) {
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            SharekhanConnect sharekhanConnect = new SharekhanConnect(null, context.getApiKey(), accessToken);

            OrderParams order = new OrderParams();
            order.customerId = context.getCustomerId();
            order.scripCode = trade.getScripCode();
            order.tradingSymbol = trade.getSymbol();
            order.exchange = trade.getExchange();
            order.transactionType = transactionType;
            order.quantity = trade.getQuantity();
            order.price = String.valueOf(price);
            order.orderType = "NORMAL";
            order.productType = "INVESTMENT";
            order.instrumentType = trade.getInstrumentType();
            
            if (trade.getStrikePrice() != null) {
                order.strikePrice = String.valueOf(trade.getStrikePrice());
            } else {
                order.strikePrice = null;
            }
            
            order.optionType = (trade.getOptionType() != null && !trade.getOptionType().isBlank()) ? trade.getOptionType() : null;
            order.expiry = (trade.getExpiry() != null && !trade.getExpiry().isBlank()) ? trade.getExpiry() : null;
            order.requestType = requestType;
            order.afterHour = ShareKhanOrderUtil.isAfterHours() ? "Y" : "N";
            order.validity = "GFD";
            order.rmsCode = "ANY";
            order.disclosedQty = 0L;
            order.channelUser = context.getClientCode();

            // Retry logic
            JSONObject response = null;
            String orderId = null;
            final int maxAttempts = 3;
            long[] backoffMs = new long[]{300L, 700L, 1500L};

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    response = sharekhanConnect.placeOrder(order);
                } catch (Exception e) {
                    log.warn("Attempt {}: placeOrder threw exception for trade {}: {}", attempt, trade.getId(), e.getMessage());
                }

                if (response != null && response.has("data")) {
                    JSONObject d = response.getJSONObject("data");
                    String respOrderId = d.optString("orderId", d.optString("orsOrderId", null));
                    if (respOrderId != null && !respOrderId.isBlank()) {
                        orderId = respOrderId;
                        break;
                    }
                }

                if (attempt < maxAttempts) {
                    try { Thread.sleep(backoffMs[Math.min(attempt-1, backoffMs.length-1)]); } catch (InterruptedException ignored) {}
                }
            }

            if (orderId == null) {
                String reason = "Broker did not return orderId after retries";
                if (response != null) {
                    String msg = response.optString("message", "");
                    if (!msg.isBlank()) reason = msg;
                }
                return OrderPlacementResult.builder()
                        .success(false)
                        .rejectionReason(reason)
                        .status("Rejected")
                        .build();
            }

            // Check for immediate execution details
            String status = "Pending";
            Double executedPrice = null;
            Double pnl = null;
            
            if (response.has("data")) {
                JSONObject d = response.getJSONObject("data");
                String respStatus = d.optString("orderStatus", "").toLowerCase();
                if (respStatus.contains("fully") || respStatus.contains("executed")) {
                    status = "Fully Executed";
                    String avgPrice = d.optString("avgPrice", "").trim();
                    if (!avgPrice.isBlank()) {
                        try { executedPrice = Double.parseDouble(avgPrice); } catch (Exception ignored) {}
                    }
                    
                    // Calculate PnL if it's a sell order and we have entry price
                    if ("S".equals(transactionType) && executedPrice != null && trade.getEntryPrice() != null) {
                        pnl = (executedPrice - trade.getEntryPrice()) * trade.getQuantity();
                    }
                }
            }

            return OrderPlacementResult.builder()
                    .success(true)
                    .orderId(orderId)
                    .status(status)
                    .executedPrice(executedPrice)
                    .pnl(pnl)
                    .build();

        } catch (Exception e) {
            log.error("Error executing Sharekhan order", e);
            return OrderPlacementResult.builder()
                    .success(false)
                    .rejectionReason("Exception: " + e.getMessage())
                    .status("Rejected")
                    .build();
        }
    }
}
