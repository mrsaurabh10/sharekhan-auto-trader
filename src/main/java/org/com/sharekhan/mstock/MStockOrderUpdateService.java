package org.com.sharekhan.mstock;

import jakarta.annotation.PreDestroy;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.monitoring.OrderPlacedEvent;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Demand-driven order streams. No broker login, market-data subscription or order submission. */
@Service @RequiredArgsConstructor @Slf4j
public class MStockOrderUpdateService {
    private static final List<TriggeredTradeStatus> TRACKED = List.of(
        TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION, TriggeredTradeStatus.TARGET_ORDER_PLACED,
        TriggeredTradeStatus.EXIT_ORDER_PLACED, TriggeredTradeStatus.EXIT_TRIGGERED, TriggeredTradeStatus.EXIT_FAILED);
    private static final Set<String> INACTIVE = Set.of("MSTOCK_PARTIAL_EXIT_RECONCILIATION_REQUIRED", "EXIT_BROKER_ORDER_INACTIVE");
    private final BrokerCredentialsRepository brokers;
    private final TriggeredTradeSetupRepository trades;
    private final AccessTokenPersistenceService tokens;
    private final CryptoService crypto;
    private final MStockOrderSocketConnector connector;
    private final ApplicationEventPublisher events;
    @Value("${app.mstock.order-updates.enabled:true}") private boolean enabled = true;
    private Clock clock = Clock.systemUTC();
    private volatile boolean stopped;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(128), task -> {var thread = new Thread(task, "mstock-order-events");thread.setDaemon(true);return thread;},
        new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();

    private static final class Session {
        final Long id;
        final String key;
        final String token;
        final Instant expiry;
        volatile long generation;
        WebSocket socket;
        CompletableFuture<WebSocket> connecting;
        Instant retryAt = Instant.EPOCH;
        Instant lastPong = Instant.EPOCH;
        Instant lastPing = Instant.EPOCH;
        int failures;
        boolean dirty;
        boolean hintQueued;
        Session(Long id, String key, String token, Instant expiry) {this.id=id;this.key=key;this.token=token;this.expiry=expiry;}
        @Override public String toString() { return "MStockOrderStream[redacted]"; }
    }
    @EventListener public void onOrderPlaced(OrderPlacedEvent event) {
        // Orders from other brokers cannot open a stream: refresh rechecks persisted account ownership and demand.
        if (stopped || event.trade() == null || event.trade().getBrokerCredentialsId() == null) return;
        if (refreshQueued.compareAndSet(false, true)) {
            try {delivery.execute(() -> {try {refresh();} finally {refreshQueued.set(false);}});}
            catch (RejectedExecutionException unavailable) {refreshQueued.set(false);}
        }
    }
    @Scheduled(initialDelayString="${app.mstock.order-updates.scan-ms:2000}", fixedDelayString="${app.mstock.order-updates.scan-ms:2000}")
    public synchronized void refresh() {
        if (stopped) return;
        if (!enabled) {closeAll();return;}
        Map<Long, List<TriggeredTradeSetupEntity>> demand = new HashMap<>();
        try {
            for (var trade : trades.findByStatusIn(TRACKED)) {
                if (needsStream(trade)) demand.computeIfAbsent(trade.getBrokerCredentialsId(), ignored -> new ArrayList<>()).add(trade);
            }
        } catch (RuntimeException unavailable) {return;} // Existing REST tracking remains independent.
        Set<Long> retained = new HashSet<>();
        for (var entry : demand.entrySet()) {
            try {
                var account = brokers.findById(entry.getKey()).orElseThrow();
                if (!"MStock".equalsIgnoreCase(account.getBrokerName()) || account.getAppUserId() == null
                    || entry.getValue().stream().noneMatch(t -> account.getAppUserId().equals(t.getAppUserId()))) continue;
                var token = tokens.findLatestByBrokerAndBrokerCredentialsId("MStock", account.getId()).orElseThrow();
                if (!account.getId().equals(token.getBrokerCredentialsId())
                    || (token.getUserId() != null && !account.getAppUserId().equals(token.getUserId()))
                    || token.getExpiry() == null || !token.getExpiry().isAfter(clock.instant()) || !text(token.getToken())) continue;
                String key = crypto.decrypt(account.getApiKey());
                if (!text(key)) continue;
                retained.add(account.getId());
                Session current = sessions.get(account.getId());
                if (current == null || !current.key.equals(key) || !current.token.equals(token.getToken()) || !current.expiry.equals(token.getExpiry())) {
                    if (current != null) close(current);
                    current = new Session(account.getId(), key, token.getToken(), token.getExpiry());
                    sessions.put(current.id, current);
                }
                connectIfDue(current);
            } catch (Exception unavailable) { /* No account fallback or authentication refresh. */ }
        }
        // A trading-enabled account alone is never demand. Close after the last pending order settles.
        for (var entry : new ArrayList<>(sessions.entrySet())) if (!retained.contains(entry.getKey())) {
            sessions.remove(entry.getKey(), entry.getValue());close(entry.getValue());
        }
    }
    private static boolean needsStream(TriggeredTradeSetupEntity trade) {
        if (trade == null || trade.getBrokerCredentialsId() == null || trade.getAppUserId() == null || INACTIVE.contains(Objects.toString(trade.getReason(), ""))) return false;
        String handle = trade.getStatus() == TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION ? trade.getOrderId() : trade.getExitOrderId();
        return text(handle) && ((handle.matches("[0-9]+") && !handle.matches("0+")) || handle.matches("MSTOCK-TAG-MS[0-9a-f]{18}"));
    }
    private void connectIfDue(Session session) {
        if (session.socket != null) {
            if (!session.lastPong.plusSeconds(90).isAfter(clock.instant())) {failed(session, session.generation);return;}
            if (!session.lastPing.plusSeconds(30).isAfter(clock.instant())) {
                session.lastPing = clock.instant();long generation = session.generation;
                try {session.socket.sendPing(ByteBuffer.wrap(new byte[]{1})).whenComplete((sent, error) -> {if (error != null) failed(session, generation);});}
                catch (RuntimeException unavailable) {failed(session, generation);}
            }
            return;
        }
        if (session.connecting != null || clock.instant().isBefore(session.retryAt)) return;
        long generation = ++session.generation;
        try {
            var opening = connector.connect(session.key, session.token, new Listener(session, generation));
            session.connecting = opening;
            opening.whenComplete((socket, error) -> {
                synchronized (this) {
                    if (!current(session, generation)) {if (socket != null) socket.abort();return;}
                    session.connecting = null;
                    if (error != null) {failed(session, generation);return;}
                    session.socket = socket;
                    session.lastPong = clock.instant();session.lastPing = clock.instant();
                    try {socket.sendText("LOGIN:" + session.token, true).orTimeout(5,TimeUnit.SECONDS).whenComplete((sent, loginError) -> {
                        synchronized (this) {
                            if (!current(session, generation)) return;
                            if (loginError != null) failed(session, generation);
                            else session.dirty = true; // Reconcile orders missed before/during connection.
                        }
                    });} catch (RuntimeException unavailable) {failed(session,generation);}
                }
            });
        } catch (Exception unavailable) {failed(session, generation);}
    }
    private boolean current(Session session, long generation) {
        return !stopped && sessions.get(session.id) == session && session.generation == generation && session.expiry.isAfter(clock.instant());
    }
    private synchronized void failed(Session session, long generation) {
        if (!current(session, generation)) return;
        close(session);
        session.retryAt = clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, ++session.failures)));
        session.dirty = true; // Fallback REST can recover even if the socket cannot reconnect.
        log.warn("MSTOCK_ORDER_STREAM_UNAVAILABLE | credential={} | retrySeconds={}", session.id, Duration.between(clock.instant(),session.retryAt).getSeconds());
    }
    private void close(Session session) {
        ++session.generation;
        var opening = session.connecting;session.connecting = null;
        var socket = session.socket;session.socket = null;
        if (opening != null) opening.cancel(true);
        if (socket != null) {
            try {socket.sendClose(WebSocket.NORMAL_CLOSURE,"Order tracking idle").orTimeout(2,TimeUnit.SECONDS).whenComplete((sent,error)->socket.abort());}
            catch (RuntimeException unavailable) {socket.abort();}
        }
    }
    private void closeAll() {sessions.values().forEach(this::close);sessions.clear();}

    @Scheduled(initialDelayString="${app.mstock.order-updates.flush-ms:250}", fixedDelayString="${app.mstock.order-updates.flush-ms:250}")
    public synchronized void flushHints() {
        if (stopped || !enabled) return;
        for (var session : sessions.values()) {
            if (!session.dirty || session.hintQueued || !session.expiry.isAfter(clock.instant())) continue;
            session.dirty = false;session.hintQueued = true;long generation = session.generation;
            try {
                delivery.execute(() -> {
                    try {if (current(session, generation)) events.publishEvent(new MStockOrderHintEvent(session.id));}
                    catch (RuntimeException unavailable) {synchronized (this) {session.dirty = true;}}
                    finally {synchronized (this) {session.hintQueued = false;}}
                });
            } catch (RejectedExecutionException saturated) {session.hintQueued = false;session.dirty = true;}
        }
    }
    private final class Listener implements WebSocket.Listener {
        private final Session session;
        private final long generation;
        private final StringBuilder fragments = new StringBuilder();
        Listener(Session session, long generation) {this.session=session;this.generation=generation;}
        @Override public void onOpen(WebSocket socket) {if (!current(session,generation)) socket.abort();else socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                synchronized (MStockOrderUpdateService.this) {
                    if (!current(session,generation)) return null;
                    if (fragments.length() + data.length() > 262144) {fragments.setLength(0);failed(session,generation);return null;}
                    fragments.append(data);
                    if (last) {String message = fragments.toString();fragments.setLength(0);receive(session,message);}
                }
            } finally {socket.request(1);}
            return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {socket.request(1);return null;}
        @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) {socket.request(1);return socket.sendPong(data);}
        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) {
            synchronized (MStockOrderUpdateService.this) {if (current(session,generation)) {session.lastPong=clock.instant();session.failures=0;}}
            socket.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {failed(session,generation);return null;}
        @Override public void onError(WebSocket socket, Throwable error) {failed(session,generation);}
    }
    private void receive(Session session, String message) {
        try {
            var root = new JSONObject(message);
            String type = root.optString("type");
            if ("error".equals(type)) {failed(session,session.generation);return;}
            if (("order".equals(type) || "trade".equals(type)) && root.has("data") && !root.isNull("data")) session.dirty = true;
            // Duplicates and out-of-order messages only request a fresh current-day snapshot.
        } catch (RuntimeException malformed) { /* No payload logging; scheduled REST still reconciles. */ }
    }
    private static boolean text(String value) {return value != null && !value.isBlank();}
    @PreDestroy public synchronized void shutdown() {stopped=true;closeAll();delivery.shutdownNow();}
}
