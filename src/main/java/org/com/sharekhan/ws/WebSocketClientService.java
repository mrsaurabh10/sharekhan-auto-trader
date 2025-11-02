package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.OrderStatusPollingService;
import org.com.sharekhan.service.PriceTriggerService;
import org.com.sharekhan.service.ScripExecutorManager;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
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
    private final LtpWebSocketHandler ltpWebSocketHandler;
    private Session session;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Set<String> subscribedScrips = ConcurrentHashMap.newKeySet();
    @Autowired
    private final LtpCacheService ltpCacheService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    private  ScripExecutorManager scripExecutorManager;



    // 🧠 Cache to track active subscriptions (e.g., NC2885, NF42120, etc.)
    private final Set<String> activeLtpSubscriptions = ConcurrentHashMap.newKeySet();

    private static final String API_KEY = TokenLoginAutomationService.apiKey; // replace with actual key
    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeSetupRepository;

    public void connect() {
        try {
            String accessToken = tokenStoreService.getAccessToken(Broker.SHAREKHAN);
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
        tradeExecutionService.subscribeForOpenTrades();
    }



    private void sendSubscribeMessage() {
        String subscribeMsg = "{\"action\":\"subscribe\",\"key\":[\"feed\",\"ack\"],\"value\":[\"\"]}";
        session.getAsyncRemote().sendText(subscribeMsg);
        String subscribeAckMsg = "{\"action\":\"ack\",\"key\":[\"\"],\"value\":[\"" + TokenLoginAutomationService.customerId + "\"]}";
        session.getAsyncRemote().sendText(subscribeAckMsg);
        log.info("📡 Subscribing Ack to {}", subscribeAckMsg);
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
        if ("heartbeat".equalsIgnoreCase(message)) {
            log.debug("💓 Heartbeat received");
            return;
        }else{
            log.info("Message received: {}", message);
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(message);
            if (json.has("message") && "feed".equalsIgnoreCase(json.get("message").asText())) {
                JsonNode data = json.has("data") ? json.get("data") : null;
                if (data == null) {
                    log.debug("Received feed message without data: {}", message);
                } else {
                    // Try various common field names for scripCode and ltp
                    Integer scripCode = null;
                    Double ltp = null;

                    // scripCode may be number or string field named scripCode, scrip, ScripCode
                    if (data.has("scripCode") && data.get("scripCode").canConvertToInt()) scripCode = data.get("scripCode").asInt();
                    else if (data.has("scrip") && data.get("scrip").canConvertToInt()) scripCode = data.get("scrip").asInt();
                    else if (data.has("ScripCode") && data.get("ScripCode").canConvertToInt()) scripCode = data.get("ScripCode").asInt();
                    else if (data.has("scripCode") && data.get("scripCode").isTextual()) {
                        try { scripCode = Integer.parseInt(data.get("scripCode").asText()); } catch (NumberFormatException ignored) {}
                    }

                    // ltp may be number or string under 'ltp' or 'last_price' or 'lastPrice'
                    if (data.has("ltp") && data.get("ltp").isNumber()) ltp = data.get("ltp").asDouble();
                    else if (data.has("ltp") && data.get("ltp").isTextual()) {
                        try { ltp = Double.parseDouble(data.get("ltp").asText()); } catch (NumberFormatException ignored) {}
                    } else if (data.has("last_price") && data.get("last_price").isNumber()) ltp = data.get("last_price").asDouble();
                    else if (data.has("lastPrice") && data.get("lastPrice").isNumber()) ltp = data.get("lastPrice").asDouble();

                    if (scripCode == null || ltp == null || scripCode == 0) {
                        log.debug("Feed message missing scripCode or ltp. scripCode={}, ltp={}, raw={}", scripCode, ltp, data);
                    } else {
                        // make effectively-final copies for use inside lambdas
                        final int sc = scripCode;
                        final double lv = ltp;

                        log.info("📊 Tick received - Scrip: {}, LTP: {}", sc, lv);
                        try {
                            ltpCacheService.updateLtp(sc, lv);
                        } catch (Exception e) {
                            log.warn("Failed to update LTP cache for {}: {}", sc, e.getMessage());
                        }

                        try {
                            scripExecutorManager.submitTriggerTask(sc, () -> priceTriggerService.evaluatePriceTrigger(sc, lv));
                        } catch (Exception e) {
                            log.error("Failed to submit trigger task for scrip {}: {}", sc, e.getMessage(), e);
                        }

                        try {
                            scripExecutorManager.submitMonitorTask(sc, () -> priceTriggerService.monitorOpenTrades(sc, lv));
                        } catch (Exception e) {
                            log.error("Failed to submit monitor task for scrip {}: {}", sc, e.getMessage(), e);
                        }

                        try {
                            ltpWebSocketHandler.broadcastLtp(sc, lv);
                        } catch (Exception e) {
                            log.warn("Failed to broadcast LTP to frontend for {}: {}", sc, e.getMessage());
                        }
                    }
                }
            } else if (json.has("message")  && "ack".equalsIgnoreCase(json.get("message").asText()) ) {
                 {
                     log.info("📊 Ack received -  {}", message);
                     JsonNode data = json.get("data");
                     String orderId = data.get("SharekhanOrderID").asText();
                     // need to find the buy price for calculation of pnl
                     String ackState = data.get("AckState").asText();
                     if ("TradeConfirmation".equalsIgnoreCase(ackState)) {
                         // Order is confirmed trigger a thread to poll the status
                         Optional<TriggeredTradeSetupEntity> triggeredTradeSetupEntity = triggeredTradeSetupRepository.findByOrderId(orderId);
                         triggeredTradeSetupEntity.ifPresent(tradeSetupEntity -> eventPublisher.publishEvent(new OrderPlacedEvent(triggeredTradeSetupEntity.get())));// .monitorOrderStatus(tradeSetupEntity));
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

    public void close() {
        try {
            if (session != null && session.isOpen()) {
                session.close();
                log.info("🔌 WebSocket session closed.");
            }
        } catch (IOException e) {
            log.error("❌ Error while closing WebSocket", e);
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


    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        log.warn("🔌 WebSocket closed: {}", closeReason);

        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (!now.isBefore(LocalTime.of(9, 10)) && !now.isAfter(LocalTime.of(15, 35))) {
            log.info("🔁 Within trading hours. Attempting immediate reconnect...");
            try {
                Thread.sleep(5000); // brief pause before reconnecting
                this.connect();
            } catch (Exception e) {
                log.error("❌ Immediate reconnect failed", e);
            }
        }
    }
}
