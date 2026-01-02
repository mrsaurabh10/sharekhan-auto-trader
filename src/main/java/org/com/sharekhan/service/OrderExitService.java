package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.model.OrderParams;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.com.sharekhan.util.CryptoService;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExitService {

    private final TokenStoreService tokenStoreService;
    private final TriggeredTradeSetupRepository triggeredTradeRepo;
    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final CryptoService cryptoService;

    @lombok.Data
    private static class BrokerCtx {
        private final Long customerId;
        private final String apiKey;
        private final String clientCode;
    }

    private String maybeDecrypt(String value) {
        if (value == null) return null;
        try { return cryptoService.decrypt(value); } catch (Exception e) { return value; }
    }

    private BrokerCtx resolveBrokerContext(Long brokerCredentialsId, Long appUserId) {
        try {
            BrokerCredentialsEntity chosen = null;
            if (brokerCredentialsId != null) {
                chosen = brokerCredentialsRepository.findById(brokerCredentialsId).orElse(null);
            }
            if (chosen == null && appUserId != null) {
                java.util.List<BrokerCredentialsEntity> list = brokerCredentialsRepository.findByAppUserId(appUserId);
                if (list != null) {
                    for (var b : list) {
                        if (b == null) continue;
                        if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")
                                && Boolean.TRUE.equals(b.getActive())) { chosen = b; break; }
                    }
                    if (chosen == null) {
                        for (var b : list) {
                            if (b == null) continue;
                            if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")) { chosen = b; break; }
                        }
                    }
                }
            }
            if (chosen == null) {
                java.util.List<BrokerCredentialsEntity> all = brokerCredentialsRepository.findAll();
                for (var b : all) {
                    if (b == null) continue;
                    if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")
                            && Boolean.TRUE.equals(b.getActive())) { chosen = b; break; }
                }
                if (chosen == null) {
                    for (var b : all) {
                        if (b == null) continue;
                        if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")) { chosen = b; break; }
                    }
                }
            }
            if (chosen == null) return null;

            Long customerId = chosen.getCustomerId();
            String apiKey = maybeDecrypt(chosen.getApiKey());
            String clientCode = maybeDecrypt(chosen.getClientCode());
            return new BrokerCtx(customerId, apiKey, clientCode);
        } catch (Exception e) {
            log.warn("Broker context resolve failed (exit): {}", e.toString());
            return null;
        }
    }

    @Transactional
    public void performSquareOff(TriggeredTradeSetupEntity trade, double exitPrice, String exitReason) {
        // Place opposite order (SELL for buy-side trades)
        OrderParams exitOrder = new OrderParams();

        BrokerCtx ctx = resolveBrokerContext(trade.getBrokerCredentialsId(), trade.getAppUserId());
        Long custId = ctx != null ? ctx.getCustomerId() : null;
        exitOrder.customerId = custId;

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
        exitOrder.channelUser = ctx != null ? ctx.getClientCode() : null;

        // Prefer a per-customer token when placing the exit order
        String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN, custId);
        if (accessToken == null) accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
        SharekhanConnect sharekhanConnect = new SharekhanConnect(null, ctx != null ? ctx.getApiKey() : null, accessToken);

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
                if (custId != null) {
                    webSocketSubscriptionService.subscribeToAck(String.valueOf(custId));
                } else {
                    log.warn("No customerId available for ACK subscription for trade {}", trade.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to subscribe ACK for customer {}: {}", custId, e.getMessage());
            }

            //monitor trade - publish event not available here; the caller can handle if needed

            log.info("📌 Live trade saved to DB for scripCode {} at LTP {}", trade.getScripCode(), exitPrice);
        }

        log.info("✅ Trade exited [{}]: PnL = {}", exitReason, trade.getPnl());
    }
}
