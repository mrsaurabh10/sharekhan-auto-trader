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
import org.com.sharekhan.util.SharekhanConsoleSilencer;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharekhanBrokerService implements ModifiableEntryBrokerService, TriggerPriceEntryBrokerService, OrderStatusBrokerService {

    private final TokenStoreService tokenStoreService;

    @Override
    public Broker getBroker() {
        return Broker.SHAREKHAN;
    }

    @Override
    public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp) {
        if (isBigTradePlus(trade)) {
            return placeBigTradePlusBracket(trade, context);
        }
        return executeSharekhanOrder(trade, context, ltp, "B", "NEW");
    }

    @Override
    public OrderPlacementResult placeTriggerPriceEntryOrder(TriggeredTradeSetupEntity trade,
                                                            BrokerContext context,
                                                            double triggerPrice,
                                                            double limitPrice) {
        return executeSharekhanOrder(trade, context, limitPrice, "B", "NEW", triggerPrice);
    }

    @Override
    public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice) {
        return executeSharekhanOrder(trade, context, exitPrice, "S", "NEW");
    }

    @Override
    public JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        try {
            if (trade == null || context == null || orderId == null || orderId.isBlank()) {
                return null;
            }
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (accessToken == null) {
                accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            }
            if (accessToken == null || context.getApiKey() == null || context.getCustomerId() == null) {
                return null;
            }
            SharekhanConnect sharekhanConnect = SharekhanConsoleSilencer.createClient(null, context.getApiKey(), accessToken);
            return SharekhanConsoleSilencer.call(() ->
                    sharekhanConnect.orderHistory(trade.getExchange(), context.getCustomerId(), orderId)
            );
        } catch (Exception e) {
            log.debug("Sharekhan order status fetch failed for trade {} order {}: {}",
                    trade != null ? trade.getId() : null, orderId, e.getMessage());
            return null;
        }
    }

    @Override
    public OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade,
                                                 BrokerContext context,
                                                 String orderId,
                                                 double newPrice) {
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            SharekhanConnect sharekhanConnect = SharekhanConsoleSilencer.createClient(null, context.getApiKey(), accessToken);

            JSONObject response = ShareKhanOrderUtil.modifyOrder(sharekhanConnect, trade, newPrice, context.getCustomerId(), context.getClientCode());
            String updatedOrderId = orderId;
            String status = "Pending";
            Double executedPrice = null;

            if (response != null && response.has("data")) {
                JSONObject data = response.getJSONObject("data");
                String respOrderId = data.optString("orderId", data.optString("orsOrderId", null));
                if (isUsableOrderId(respOrderId)) {
                    updatedOrderId = respOrderId;
                }
                String respStatus = data.optString("orderStatus", "");
                if (ShareKhanOrderUtil.isFullyExecutedStatus(respStatus)) {
                    status = "Fully Executed";
                    String avgPrice = data.optString("avgPrice", "").trim();
                    if (!avgPrice.isBlank()) {
                        try {
                            executedPrice = Double.parseDouble(avgPrice);
                        } catch (NumberFormatException ignore) { }
                    }
                }
            }

            return OrderPlacementResult.builder()
                    .success(true)
                    .orderId(updatedOrderId)
                    .status(status)
                    .attemptedPrice(newPrice)
                    .executedPrice(executedPrice)
                    .build();
        } catch (com.sharekhan.http.exceptions.SharekhanAPIException e) {
            log.warn("Sharekhan API rejected entry modify for trade {} orderId {}: {}", trade.getId(), orderId, e.getMessage());
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .attemptedPrice(newPrice)
                    .rejectionReason("ENTRY_MODIFY_REJECTED: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.warn("Modify entry order failed for trade {} orderId {}: {}", trade.getId(), orderId, e.getMessage());
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .attemptedPrice(newPrice)
                    .rejectionReason("ENTRY_MODIFY_FAILED: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public void cancelEntryOrder(TriggeredTradeSetupEntity trade,
                                 BrokerContext context,
                                 String orderId) {
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            SharekhanConnect sharekhanConnect = SharekhanConsoleSilencer.createClient(null, context.getApiKey(), accessToken);
            ShareKhanOrderUtil.cancelOrder(sharekhanConnect, trade, orderId, context.getCustomerId(), context.getClientCode());
            log.info("🚫 Cancelled entry order {} for trade {}", orderId, trade.getId());
        } catch (com.sharekhan.http.exceptions.SharekhanAPIException e) {
            log.warn("Sharekhan API rejected entry cancel for trade {} orderId {}: {}", trade.getId(), orderId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to cancel entry order {} for trade {}: {}", orderId, trade.getId(), e.getMessage());
        }
    }

    private OrderPlacementResult executeSharekhanOrder(TriggeredTradeSetupEntity trade,
                                                       BrokerContext context,
                                                       double price,
                                                       String transactionType,
                                                       String requestType) {
        return executeSharekhanOrder(trade, context, price, transactionType, requestType, null);
    }

    /**
     * BIGTRADE+ is a Sharekhan application-level bracket, not a normal SDK
     * order.  The published SDK model omits bookProfitPrice and childSlPrice,
     * so submit the documented BKT JSON directly rather than silently dropping
     * the protective child legs.
     */
    private OrderPlacementResult placeBigTradePlusBracket(TriggeredTradeSetupEntity trade, BrokerContext context) {
        String validationError = validateBigTradePlus(trade, context);
        if (validationError != null) {
            return rejected(validationError, trade != null ? trade.getEntryPrice() : null);
        }
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (!StringUtils.hasText(accessToken)) {
                accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            }
            if (!StringUtils.hasText(accessToken)) {
                return rejected("Sharekhan access token is unavailable", trade.getEntryPrice());
            }

            JSONObject order = bigTradePlusPayload(trade, context);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.sharekhan.com/skapi/services/orders"))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("api-key", context.getApiKey())
                    .header("access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(order.toString()))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject body = new JSONObject(response.body());
            JSONObject data = body.optJSONObject("data");
            String orderId = data != null ? data.optString("orderId", "") : "";
            if (response.statusCode() / 100 != 2 || !isUsableOrderId(orderId)) {
                String message = body.optString("message", body.optString("errormsg", "Sharekhan did not return an order id"));
                if (data != null && StringUtils.hasText(data.optString("errormsg"))) {
                    message = data.optString("errormsg");
                }
                return rejected("BTP_REJECTED: " + message, trade.getEntryPrice());
            }
            log.info("BTP_BRACKET_ACCEPTED | tradeId={} | orderId={} | symbol={} | quantity={} | entry={} | target={} | stopLoss={}",
                    trade.getId(), orderId, trade.getSymbol(), trade.getQuantity(), trade.getEntryPrice(),
                    trade.getTarget1(), trade.getStopLoss());
            return OrderPlacementResult.builder().success(true).orderId(orderId).status("Pending")
                    .attemptedPrice(trade.getEntryPrice()).build();
        } catch (Exception e) {
            log.warn("BTP bracket placement failed for trade {}: {}", trade != null ? trade.getId() : null, e.getMessage());
            return rejected("BTP_REQUEST_FAILED: " + e.getMessage(), trade != null ? trade.getEntryPrice() : null);
        }
    }

    static JSONObject bigTradePlusPayload(TriggeredTradeSetupEntity trade, BrokerContext context) {
        JSONObject order = new JSONObject();
        order.put("orderId", "");
        order.put("customerId", context.getCustomerId());
        order.put("scripCode", trade.getScripCode());
        order.put("tradingSymbol", trade.getSymbol());
        order.put("exchange", trade.getExchange());
        order.put("transactionType", "B");
        order.put("quantity", trade.getQuantity());
        order.put("disclosedQty", 0);
        order.put("triggerPrice", 0);
        order.put("price", formatOrderPrice(trade.getEntryPrice()));
        order.put("rmsCode", "ANY");
        order.put("afterHour", "N");
        order.put("orderType", "BKT");
        order.put("channelUser", context.getClientCode());
        order.put("validity", "GFD");
        order.put("requestType", "NEW");
        order.put("productType", "BIGTRADEPLUS");
        order.put("bookProfitPrice", formatOrderPrice(trade.getTarget1()));
        order.put("childSlPrice", formatOrderPrice(trade.getStopLoss()));
        return order;
    }

    private static String formatOrderPrice(Double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private boolean isBigTradePlus(TriggeredTradeSetupEntity trade) {
        return trade != null && "BIGTRADEPLUS".equalsIgnoreCase(trade.getBrokerProductType());
    }

    private String validateBigTradePlus(TriggeredTradeSetupEntity trade, BrokerContext context) {
        if (trade == null || context == null || context.getCustomerId() == null || !StringUtils.hasText(context.getApiKey())) return "BTP requires Sharekhan credentials";
        if (!"NC".equalsIgnoreCase(trade.getExchange())) return "BTP is currently limited to NSE cash equities";
        if (trade.getScripCode() == null || !StringUtils.hasText(trade.getSymbol()) || trade.getQuantity() == null || trade.getQuantity() <= 0) return "BTP requires a valid cash-equity instrument and quantity";
        if (!validPrice(trade.getEntryPrice()) || !validPrice(trade.getStopLoss()) || !validPrice(trade.getTarget1())) return "BTP requires entry, stop loss, and target 1";
        if (!(trade.getStopLoss() < trade.getEntryPrice() && trade.getEntryPrice() < trade.getTarget1())) return "BTP buy geometry must be stopLoss < entryPrice < target1";
        return null;
    }

    private boolean validPrice(Double value) { return value != null && Double.isFinite(value) && value > 0d; }

    private OrderPlacementResult rejected(String reason, Double attemptedPrice) {
        return OrderPlacementResult.builder().success(false).status("Rejected").attemptedPrice(attemptedPrice).rejectionReason(reason).build();
    }

    private OrderPlacementResult executeSharekhanOrder(TriggeredTradeSetupEntity trade,
                                                       BrokerContext context,
                                                       double price,
                                                       String transactionType,
                                                       String requestType,
                                                       Double triggerPrice) {
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, context.getCustomerId());
            if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
            SharekhanConnect sharekhanConnect = SharekhanConsoleSilencer.createClient(null, context.getApiKey(), accessToken);

            OrderParams order = new OrderParams();
            order.customerId = context.getCustomerId();
            order.scripCode = trade.getScripCode();
            order.tradingSymbol = trade.getSymbol();
            order.exchange = trade.getExchange();
            order.transactionType = transactionType;
            order.quantity = trade.getQuantity();
            order.price = String.valueOf(price);
            if (triggerPrice != null) {
                order.triggerPrice = String.valueOf(triggerPrice);
            }
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
            String stage = "S".equalsIgnoreCase(transactionType)
                    ? "EXIT"
                    : triggerPrice != null ? "ENTRY_TRIGGER" : "ENTRY";

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long attemptStartedAt = System.nanoTime();
                try {
                    log.info("{}_BROKER_ATTEMPT | tradeId={} | symbol={} | attempt={} | requestType={} | transactionType={} | attemptedPrice={} | triggerPrice={}",
                            stage, trade.getId(), trade.getSymbol(), attempt, requestType, transactionType, price, order.triggerPrice);
                    response = SharekhanConsoleSilencer.call(() -> sharekhanConnect.placeOrder(order));
                } catch (Exception e) {
                    log.warn("{}_BROKER_ATTEMPT_FAILED | tradeId={} | attempt={} | attemptedPrice={} | triggerPrice={} | elapsedMs={} | reason={}",
                            stage, trade.getId(), attempt, price, order.triggerPrice, elapsedMillis(attemptStartedAt), e.getMessage());
                }

                if (response != null && response.has("data")) {
                    JSONObject d = response.getJSONObject("data");
                    String respOrderId = d.optString("orderId", d.optString("orsOrderId", null));
                    if (isUsableOrderId(respOrderId)) {
                        orderId = respOrderId;
                        long sdkHttpMs = elapsedMillis(attemptStartedAt);
                        log.info("{}_BROKER_ATTEMPT_ACCEPTED | tradeId={} | attempt={} | attemptedPrice={} | orderId={} | sdkHttpMs={} | totalMs={}",
                                stage, trade.getId(), attempt, price, orderId, sdkHttpMs, sdkHttpMs);
                        break;
                    }
                }

                log.warn("{}_BROKER_ATTEMPT_NO_ORDER_ID | tradeId={} | attempt={} | attemptedPrice={} | elapsedMs={}",
                        stage, trade.getId(), attempt, price, elapsedMillis(attemptStartedAt));

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
                        .attemptedPrice(price)
                        .status("Rejected")
                        .build();
            }

            // Check for immediate execution details
            String status = "Pending";
            Double executedPrice = null;
            Double pnl = null;
            
            if (response.has("data")) {
                JSONObject d = response.getJSONObject("data");
                String respStatus = d.optString("orderStatus", "");
                if (ShareKhanOrderUtil.isFullyExecutedStatus(respStatus)) {
                    status = "Fully Executed";
                    String avgPrice = d.optString("avgPrice", "").trim();
                    if (!avgPrice.isBlank()) {
                        try { executedPrice = Double.parseDouble(avgPrice); } catch (Exception ignored) {}
                    }
                    
                    // Calculate PnL if it's a sell order and we have entry price
                    if ("S".equals(transactionType) && executedPrice != null) {
                        Double entryPriceForPnl = resolveEntryPriceForPnl(trade);
                        if (entryPriceForPnl != null) {
                            pnl = (executedPrice - entryPriceForPnl) * trade.getQuantity();
                        }
                    }
                }
            }

            return OrderPlacementResult.builder()
                    .success(true)
                    .orderId(orderId)
                    .status(status)
                    .attemptedPrice(price)
                    .executedPrice(executedPrice)
                    .pnl(pnl)
                    .build();

        } catch (Exception e) {
            log.error("Error executing Sharekhan order", e);
            return OrderPlacementResult.builder()
                    .success(false)
                    .rejectionReason("Exception: " + e.getMessage())
                    .attemptedPrice(price)
                    .status("Rejected")
                    .build();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private boolean isUsableOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        String normalized = orderId.trim();
        return !"0".equals(normalized)
                && !"NA".equalsIgnoreCase(normalized)
                && !"null".equalsIgnoreCase(normalized);
    }

    private Double resolveEntryPriceForPnl(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getActualEntryPrice() != null) {
            return trade.getActualEntryPrice();
        }
        if (usesSpotReference(trade)) {
            log.warn("Cannot compute PnL for spot-referenced trade {} because actualEntryPrice is missing. entryPrice={} is a reference price.",
                    trade.getId(), trade.getEntryPrice());
            return null;
        }
        return trade.getEntryPrice();
    }

    private boolean usesSpotReference(TriggeredTradeSetupEntity trade) {
        return Boolean.TRUE.equals(trade.getUseSpotForEntry())
                || Boolean.TRUE.equals(trade.getUseSpotForSl())
                || Boolean.TRUE.equals(trade.getUseSpotForTarget())
                || Boolean.TRUE.equals(trade.getUseSpotPrice());
    }
}
