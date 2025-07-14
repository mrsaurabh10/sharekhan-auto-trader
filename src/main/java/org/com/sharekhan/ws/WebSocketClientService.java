package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.OrderStatusPollingService;
import org.com.sharekhan.service.PriceTriggerService;
import org.com.sharekhan.service.TradeExecutionService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.swing.text.html.Option;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ClientEndpoint
@RequiredArgsConstructor
public class WebSocketClientService  {

    private final TokenStoreService tokenStoreService;
    private final PriceTriggerService priceTriggerService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketConnector webSocketConnector;
    private Session session;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Set<String> subscribedScrips = ConcurrentHashMap.newKeySet();
    @Autowired
    private final LtpCacheService ltpCacheService;

    // 🧠 Cache to track active subscriptions (e.g., NC2885, NF42120, etc.)
    private final Set<String> activeLtpSubscriptions = ConcurrentHashMap.newKeySet();

    private static final String API_KEY = TokenLoginAutomationService.apiKey; // replace with actual key
    @Autowired
    @Lazy
    private OrderStatusPollingService orderStatusPollingService;
    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeSetupRepository;

    @PostConstruct
    public void init() {
        try {
            String accessToken = tokenStoreService.getAccessToken();
            String wsUrl = String.format("wss://stream.sharekhan.com/skstream/api/stream?ACCESS_TOKEN=%s&API_KEY=%s", accessToken, API_KEY);
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, new URI(wsUrl));
        } catch (Exception e) {
            log.error("❌ Failed to connect WebSocket", e);
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("✅ Connected to Sharekhan WebSocket");
        this.session = session;
        webSocketConnector.setSession(session);
        sendSubscribeMessage();
    }



    private void sendSubscribeMessage() {
        String subscribeMsg = "{\"action\":\"subscribe\",\"key\":[\"feed\",\"ack\"],\"value\":[\"\"]}";
        session.getAsyncRemote().sendText(subscribeMsg);
        String subscribeAckMsg = "{\"action\":\"ack\",\"key\":[\"\"],\"value\":[\"" + String.valueOf(TokenLoginAutomationService.customerId)+ "\"]}";
        session.getAsyncRemote().sendText(subscribeMsg);
        log.info("📡 Subscribing Ack to {}",subscribeAckMsg);
    }


    public void subscribeLTP(String scripCode) {
        if (!isConnected()) return;
        if (subscribedScrips.contains(scripCode)) return;

        String feedMsg = String.format("{\"action\":\"feed\",\"key\":[\"ltp\"],\"value\":[\"%s\"]}", scripCode);
        session.getAsyncRemote().sendText(feedMsg);
        subscribedScrips.add(scripCode);
        log.info("📡 Subscribed to LTP for {}", scripCode);
    }

    @OnMessage
    public void onMessage(String message) {
        log.info("💓 Message received {}", message);
        if ("heartbeat".equalsIgnoreCase(message)) {
            log.debug("💓 Heartbeat received");
            return;
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(message);
            if (json.has("message") && "feed".equals(json.get("message").asText())
                    && json.has("data") && json.get("data").has("ltp")) {
                JsonNode data = json.get("data");
                int scripCode = data.get("scripCode").asInt();
                double ltp = data.get("ltp").asDouble();
                log.info("📊 Tick received - Scrip: {}, LTP: {}", scripCode, ltp);
                ltpCacheService.updateLtp(scripCode, ltp);
                priceTriggerService.evaluatePriceTrigger(scripCode,ltp);
                priceTriggerService.monitorOpenTrades(scripCode,ltp);
            }else if (json.has("message")  && "ack".equals(json.get("message").asText())
                    && json.has("data") && json.get("data").has("AckState")) {
                {
                    log.info("📊 Ack received -  {}", message);
                    JsonNode data = json.get("data");
                    String orderId = data.get("SharekhanOrderID").asText();
                    // need to find the buy price for calculation of pnl
                    String ackState = data.get("AckState").asText();
                    if ("TradeConfirmation".equalsIgnoreCase(ackState)) {
                        // Order is confirmed trigger a thread to poll the status
                        Optional<TriggeredTradeSetupEntity> triggeredTradeSetupEntity = triggeredTradeSetupRepository.findByOrderId(orderId);
                        triggeredTradeSetupEntity.ifPresent(tradeSetupEntity -> orderStatusPollingService.monitorOrderStatus(tradeSetupEntity));
                        //tradeExecutionService.markOrderExecuted(orderId);
                    } else if ("NewOrderRejection".equalsIgnoreCase(ackState)) {
                        tradeExecutionService.markOrderRejected(orderId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Failed to parse message: {}", message, e);
        }
    }

//    @Override
//    public void subscribeToScrip(String feedKey) {
//        if (session == null || !session.isOpen()) return;
//
//        if (activeLtpSubscriptions.contains(feedKey)) {
//            log.debug("⚠️ Already subscribed to feed: {}", feedKey);
//            return;
//        }
//
//        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
//        payload.put("action", "feed");
//        payload.putArray("key").add("ltp");
//        payload.putArray("value").add(feedKey);
//
//        session.getAsyncRemote().sendText(payload.toString());
//        activeLtpSubscriptions.add(feedKey);
//        log.info("📡 Subscribed to LTP for scrip: {}", feedKey);
//    }
//
//    @Override
//    public void unsubscribeFromScrip(String feedKey) {
//        if (session == null || !session.isOpen()) return;
//
//        if (!activeLtpSubscriptions.contains(feedKey)) {
//            log.debug("⚠️ Not currently subscribed to feed: {}", feedKey);
//            return;
//        }
//
//        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
//        payload.put("action", "unsubscribe");
//        payload.putArray("key").add("ltp");
//        payload.putArray("value").add(feedKey);
//
//        session.getAsyncRemote().sendText(payload.toString());
//        activeLtpSubscriptions.remove(feedKey);
//        log.info("🔌 Unsubscribed from LTP feed: {}", feedKey);
//    }

    public boolean isSubscribed(String feedKey) {
        return activeLtpSubscriptions.contains(feedKey);
    }

//    @Override
//    public void subscribeToAck(String customerId) {
//        if (session == null || !session.isOpen()) return;
//
//        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
//        payload.put("action", "ack");
//        payload.putArray("key").add("");
//        payload.putArray("value").add(customerId);
//
//        session.getAsyncRemote().sendText(payload.toString());
//        log.info("📡 Subscribed to ACK for customer {}", customerId);
//    }



    @OnError
    public void onError(Session session, Throwable thr) {
        log.error("❗ WebSocket error", thr);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        log.warn("🔌 WebSocket closed: {}", closeReason);
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }
}
