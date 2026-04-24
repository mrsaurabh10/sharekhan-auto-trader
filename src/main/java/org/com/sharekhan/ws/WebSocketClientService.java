package org.com.sharekhan.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.ws.QuotePayloadParser.BidAsk;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.PriceTriggerService;
import org.com.sharekhan.service.ScripExecutorManager;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
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
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final QuoteCacheService quoteCacheService;
    private Session session;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Set<String> subscribedScrips = ConcurrentHashMap.newKeySet();
    @Autowired
    private final LtpCacheService ltpCacheService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    private  ScripExecutorManager scripExecutorManager;

    @Autowired
    private org.com.sharekhan.repository.ScriptMasterRepository scriptMasterRepository;

    // Cache for scripCode -> ScriptMasterEntity to avoid DB hits on every tick
    private final Map<Integer, ScriptMasterEntity> scriptMasterCache = new ConcurrentHashMap<>();

    // 🧠 Cache to track active subscriptions (e.g., NC2885, NF42120, etc.)
    private final Set<String> activeLtpSubscriptions = ConcurrentHashMap.newKeySet();

    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeSetupRepository;

    public void connect() {
        try {
            // Prefer the first non-expired token for SHAREKHAN: global, per-customer in-memory, then persisted latest
            TokenStoreService.TokenInfo tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.SHAREKHAN);
            
            if (tokenInfo == null || tokenInfo.getToken() == null || tokenInfo.getToken().isBlank()) {
                log.warn("⚠️ No valid access token available for SHAREKHAN websocket connection. Skipping connect; will retry on scheduled reconnects.");
                return;
            }
            
            String accessToken = tokenInfo.getToken();
            String apiKey = tokenInfo.getApiKey();
            
            if (apiKey == null || apiKey.isBlank()) {
                 log.warn("⚠️ No valid API Key available for SHAREKHAN websocket connection. Skipping connect.");
                 return;
            }

            String wsUrl = String.format("wss://stream.sharekhan.com/skstream/api/stream?ACCESS_TOKEN=%s&API_KEY=%s", accessToken, apiKey);
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
        
        // Reset subscription helper state on new connection to ensure we re-subscribe correctly
        webSocketSubscriptionHelper.reset();

        sendSubscribeMessage();
        tradeExecutionService.subscribeForOpenTrades();
    }



    private void sendSubscribeMessage() {
        String subscribeMsg = "{\"action\":\"subscribe\",\"key\":[\"feed\",\"ack\"],\"value\":[\"\"]}";
        session.getAsyncRemote().sendText(subscribeMsg);
        // NOTE: Per-customer ACK subscriptions will be created when trades are placed.
        log.info("📡 Sent initial subscribe message (ACK subscriptions will be created per customer when trades are placed)");
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
        } else {
            // keep a lightweight debug entry; avoid printing full raw message which is noisy
            log.debug("WebSocket message received (type will be inspected)");
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(message);
            if (json.has("message") && "feed".equalsIgnoreCase(json.get("message").asText())) {
                 JsonNode data = json.has("data") ? json.get("data") : null;
                 if (data == null) {
                    log.debug("Received feed message without data");
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
                    ltp = extractFirstDouble(data, "ltp", "last_price", "lastPrice", "lastTradedPrice");
                    BidAsk bidAsk = QuotePayloadParser.extractBestBidAsk(data);
                    Double bestBid = bidAsk.bid();
                    Double bestAsk = bidAsk.ask();

                    if (scripCode == null || scripCode == 0) {
                        log.debug("Feed message missing scripCode");
                    } else if (ltp == null && bestBid == null && bestAsk == null) {
                        log.debug("Feed message for {} missing price fields", scripCode);
                    } else {
                        if (bestBid != null || bestAsk != null) {
                            log.info("📘 Depth update - scripCode={} bid={} ask={} ltp={}",
                                    scripCode,
                                    bestBid != null ? bestBid : "NA",
                                    bestAsk != null ? bestAsk : "NA",
                                    ltp != null ? ltp : "NA");
                        } else if (data.has("depth")) {
                            log.debug("Depth payload present for {} but no top-of-book price parsed: {}", scripCode, data.get("depth"));
                        }
                        processLtpUpdate(scripCode, ltp, bestBid, bestAsk);
                    }
                }
            } else if (json.has("message")  && "ack".equalsIgnoreCase(json.get("message").asText()) ) {
                JsonNode data = json.get("data");
                if (data == null) {
                    log.debug("ACK received with empty data");
                } else {
                    String orderId = data.has("SharekhanOrderID") ? data.get("SharekhanOrderID").asText() : "";
                    String ackState = data.has("AckState") ? data.get("AckState").asText() : "";
                    if ("TradeConfirmation".equalsIgnoreCase(ackState)) {
                        // Order confirmed - try to map to either orderId or exitOrderId
                        Optional<TriggeredTradeSetupEntity> triggeredTradeSetupEntity = triggeredTradeSetupRepository.findByOrderId(orderId);
                        if (triggeredTradeSetupEntity.isEmpty()) {
                            triggeredTradeSetupEntity = triggeredTradeSetupRepository.findByExitOrderId(orderId);
                        }
                        if (triggeredTradeSetupEntity.isPresent()) {
                            TriggeredTradeSetupEntity t = triggeredTradeSetupEntity.get();
                            eventPublisher.publishEvent(new OrderPlacedEvent(t));
                            log.info("✅ ACK TradeConfirmation - orderId={} mapped to tradeId={} status={}", orderId, t.getId(), t.getStatus());
                        } else {
                            log.info("✅ ACK TradeConfirmation - orderId={} no matching trade found", orderId);
                        }
                    } else if ("NewOrderRejection".equalsIgnoreCase(ackState)) {
                        Optional<TriggeredTradeSetupEntity> t = triggeredTradeSetupRepository.findByOrderId(orderId);
                        if (t.isEmpty()) t = triggeredTradeSetupRepository.findByExitOrderId(orderId);
                        if (t.isPresent()) {
                            tradeExecutionService.markOrderRejected(orderId);
                            log.warn("🔴 ACK NewOrderRejection - orderId={} mapped to tradeId={}", orderId, t.get().getId());
                        } else {
                            tradeExecutionService.markOrderRejected(orderId);
                            log.warn("🔴 ACK NewOrderRejection - orderId={} (no mapped trade)", orderId);
                        }
                    } else {
                        log.debug("ACK received - orderId={} ackState={}", orderId, ackState);
                    }
                }
             }
         } catch (Exception e) {
            log.error("❌ Failed to parse WebSocket message", e);
         }
    }

    private Double extractFirstDouble(JsonNode data, String... fields) {
        if (data == null) {
            return null;
        }
        for (String field : fields) {
            if (!data.has(field)) {
                continue;
            }
            JsonNode node = data.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isNumber()) {
                return node.asDouble();
            }
            if (node.isTextual()) {
                try {
                    return Double.parseDouble(node.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    public void processLtpUpdate(Integer scripCode, Double ltp) {
        processLtpUpdate(scripCode, ltp, null, null);
    }

    public void processLtpUpdate(Integer scripCode, Double ltp, Double bestBid, Double bestAsk) {
        final int sc = scripCode;
        final Double lv = ltp;

        if (lv != null) {
            log.info("📊 LTP Tick received - scripCode={}, ltp={}", sc, lv);
            try {
                ltpCacheService.updateLtp(sc, lv);
            } catch (Exception e) {
                log.warn("Failed to update LTP cache for {}: {}", sc, e.getMessage());
            }
        }

        try {
            quoteCacheService.recordQuote(sc, bestBid, bestAsk, lv);
        } catch (Exception e) {
            log.warn("Failed to record quote snapshot for {}: {}", sc, e.getMessage());
        }

        double callbackPrice = lv != null ? lv
                : bestAsk != null ? bestAsk
                : bestBid != null ? bestBid
                : 0d;

        if (callbackPrice > 0) {
            try {
                scripExecutorManager.submitTriggerTask(sc, () -> priceTriggerService.evaluatePriceTrigger(sc, callbackPrice));
            } catch (Exception e) {
                log.error("Failed to submit trigger task for scrip {}: {}", sc, e.getMessage(), e);
            }

            try {
                scripExecutorManager.submitMonitorTask(sc, () -> priceTriggerService.monitorOpenTrades(sc, callbackPrice));
            } catch (Exception e) {
                log.error("Failed to submit monitor task for scrip {}: {}", sc, e.getMessage(), e);
            }
        }

        Double broadcastPrice = lv != null ? lv : callbackPrice;

        try {
            ScriptMasterEntity script = scriptMasterCache.get(sc);
            if (script == null && !scriptMasterCache.containsKey(sc)) {
                script = scriptMasterRepository.findByScripCode(sc);
                if (script != null) {
                    scriptMasterCache.put(sc, script);
                }
            }

            String instrument = null;
            String exchange = null;
            if (script != null) {
                instrument = script.getTradingSymbol();
                exchange = script.getExchange();

                if ("NC".equalsIgnoreCase(exchange)) exchange = "NSE";
                else if ("BC".equalsIgnoreCase(exchange)) exchange = "BSE";
                else if ("NF".equalsIgnoreCase(exchange)) exchange = "NFO";
                else if ("BF".equalsIgnoreCase(exchange)) exchange = "BFO";
            }
            if (broadcastPrice != null) {
                ltpWebSocketHandler.broadcastLtp(sc, broadcastPrice, instrument, exchange);
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast quote to frontend for {}: {}", sc, e.getMessage());
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
