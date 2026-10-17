package org.com.sharekhan.dhan;

import jakarta.annotation.PreDestroy;
import java.net.http.WebSocket;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.entity.*;
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

/** Per-credential individual order streams. No automatic broker login or trade actions. */
@Service @RequiredArgsConstructor @Slf4j
public class DhanOrderUpdateService {
    private final BrokerCredentialsRepository brokers;
    private final TriggeredTradeSetupRepository trades;
    private final AccessTokenPersistenceService tokens;
    private final CryptoService crypto;
    private final DhanOrderSocketConnector connector;
    private final ApplicationEventPublisher events;
    @Value("${app.dhan.order-updates.enabled:true}") private boolean enabled=true;
    private Clock clock=Clock.systemUTC();
    private volatile boolean stopped;
    private final Map<Long,Session> sessions=new ConcurrentHashMap<>();
    private final ExecutorService delivery=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256),r->{var t=new Thread(r,"dhan-order-updates");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final List<TriggeredTradeStatus> TRACKED=List.of(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
        TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED);
    private static class Session {
        final Long id; final String client; final String token; final Instant expiry;
        WebSocket socket; CompletableFuture<WebSocket> connecting; Instant retryAt=Instant.EPOCH; int failures; volatile long generation; volatile Instant lastPong=Instant.EPOCH; Instant lastPing=Instant.EPOCH;
        final Map<String,Version> versions=new LinkedHashMap<>();
        Session(Long id,String client,String token,Instant expiry){this.id=id;this.client=client;this.token=token;this.expiry=expiry;}
        @Override public String toString(){return "DhanStream[redacted]";}
    }
    private record Version(Instant time,long filled,String status) {}

    @EventListener public void onOrderPlaced(OrderPlacedEvent ignored) { enqueue(this::refresh); }
    @Scheduled(initialDelayString="${app.dhan.order-updates.scan-ms:5000}",fixedDelayString="${app.dhan.order-updates.scan-ms:5000}")
    public synchronized void refresh() {
        if (stopped) return;
        if (!enabled) {sessions.values().forEach(this::close);sessions.clear();return;}
        Set<Long> needed=new HashSet<>();
        for (var trade:trades.findByStatusIn(TRACKED)) if(trade.getBrokerCredentialsId()!=null) needed.add(trade.getBrokerCredentialsId());
        Set<Long> retained=new HashSet<>();
        for (var broker:brokers.findAllByBrokerName("Dhan")) {
            Long id=broker.getId();
            if(id==null || broker.getAppUserId()==null || (!needed.contains(id) && !(Boolean.TRUE.equals(broker.getActive()) && Boolean.TRUE.equals(broker.getTradingEnabled())))) continue;
            try {
                var token=tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",id).orElseThrow();
                if(token.getExpiry()==null || !token.getExpiry().isAfter(clock.instant()) || token.getToken()==null || token.getToken().isBlank()) continue;
                String client=crypto.decrypt(broker.getClientCode()).trim();
                if(!client.matches("[0-9]{1,20}")) continue;
                retained.add(id);
                Session current=sessions.get(id);
                if(current==null || !current.client.equals(client) || !current.token.equals(token.getToken()) || !current.expiry.equals(token.getExpiry())) {
                    if(current!=null) close(current);
                    current=new Session(id,client,token.getToken(),token.getExpiry());sessions.put(id,current);
                }
                connectIfDue(current);
            } catch(Exception ignored) { /* Invalid scoped credentials suspend streaming; REST recovery remains independent. */ }
        }
        for(var entry:new ArrayList<>(sessions.entrySet())) if(!retained.contains(entry.getKey())) {
            sessions.remove(entry.getKey(),entry.getValue());close(entry.getValue());
        }
    }
    private void connectIfDue(Session session) {
        synchronized(session) {
            if(session.socket!=null) {
                if(session.lastPong.plusSeconds(90).isBefore(clock.instant())) {failed(session,session.generation);return;}
                if(session.lastPing.plusSeconds(30).isBefore(clock.instant())) {
                    session.lastPing=clock.instant();long generation=session.generation;
                    session.socket.sendPing(java.nio.ByteBuffer.wrap(new byte[]{1})).whenComplete((sent,error)->{if(error!=null)failed(session,generation);});
                }
                return;
            }
            if(session.connecting!=null || clock.instant().isBefore(session.retryAt))return;
            long generation=++session.generation;
            var listener=new Listener(session,generation);
            CompletableFuture<WebSocket> opening;
            try {opening=connector.connect(listener);}catch(Exception ignored){failed(session,generation);return;}
            session.connecting=opening;
            opening.whenComplete((socket,error)->{
                synchronized(session) {
                    if(!current(session,generation)) {if(socket!=null)socket.abort();return;}
                    session.connecting=null;
                    if(!current(session)) {if(socket!=null) socket.abort();return;}
                    if(error!=null) {failed(session,generation);return;}
                    session.socket=socket;session.lastPong=clock.instant();session.lastPing=clock.instant();
                    String auth=new JSONObject().put("LoginReq",new JSONObject().put("MsgCode",42)
                        .put("ClientId",session.client).put("Token",session.token)).put("UserType","SELF").toString();
                    socket.sendText(auth,true).whenComplete((sent,failure)->{
                        if(failure!=null)failed(session,generation);
                        else enqueue(()->{if(current(session,generation)){log.info("Dhan order stream login sent for credential {}",session.id);events.publishEvent(new DhanOrderStreamConnectedEvent(session.id));}});
                    });
                }
            });
        }
    }
    private boolean current(Session session) {return !stopped && sessions.get(session.id)==session && session.expiry.isAfter(clock.instant());}
    private boolean current(Session session,long generation){return current(session) && session.generation==generation;}
    private void failed(Session session,long generation) {
        synchronized(session) {
            if(!current(session,generation))return;
            session.generation++;
            var socket=session.socket;session.socket=null;
            if(socket!=null)socket.abort();
            session.failures=Math.min(session.failures+1,6);
            session.retryAt=clock.instant().plusSeconds(Math.min(60,1L<<session.failures));
            log.warn("Dhan order stream disconnected for credential {}; reconnect backoff {} seconds; REST fallback remains available",session.id,Math.min(60,1L<<session.failures));
        }
    }
    private void close(Session session) {
        synchronized(session) {
            session.generation++;
            var opening=session.connecting;session.connecting=null;
            var socket=session.socket;session.socket=null;
            if(opening!=null)opening.cancel(true);
            if(socket!=null)socket.abort();
            session.versions.clear();
        }
    }
    private void enqueue(Runnable work) {
        if(stopped)return;
        try {delivery.execute(()->{try{work.run();}catch(Exception ignored){/* REST reconciles any lost event. */}});}
        catch(RejectedExecutionException ignored){/* Bounded queue: recovery uses REST rather than unbounded buffering. */}
    }
    private class Listener implements WebSocket.Listener {
        final Session session;final long generation;final StringBuilder fragment=new StringBuilder();
        Listener(Session session,long generation){this.session=session;this.generation=generation;}
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last) {
            try {
                if(!current(session,generation) || fragment.length()+data.length()>65536){socket.abort();failed(session,generation);return null;}
                fragment.append(data);
                if(last){String message=fragment.toString();fragment.setLength(0);receive(session,generation,message);}
            } catch(Exception ignored){/* Ignore malformed payloads, never log raw messages. */}
            finally{socket.request(1);}
            return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket socket,java.nio.ByteBuffer data){socket.request(1);return socket.sendPong(data);}
        @Override public CompletionStage<?> onPong(WebSocket socket,java.nio.ByteBuffer data){if(current(session,generation))session.lastPong=clock.instant();socket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason){if(current(session,generation))failed(session,generation);return null;}
        @Override public void onError(WebSocket socket,Throwable error){if(current(session,generation))failed(session,generation);}
    }
    private void receive(Session session,long generation,String message) {
        if(!current(session,generation))return;
        JSONObject root=new JSONObject(message);
        if(!"order_alert".equals(root.optString("Type")))return;
        JSONObject data=root.getJSONObject("Data");
        if(!session.client.equals(data.getString("ClientId")))return;
        String id=data.getString("OrderNo"),correlation=data.optString("CorrelationId","");
        if(!id.matches("[A-Za-z0-9_-]{1,64}"))return;
        String status=data.getString("Status").trim().toUpperCase(Locale.ROOT).replace(' ','_');
        if(!Set.of("TRANSIT","PENDING","PART_TRADED","TRADED","REJECTED","CANCELLED","EXPIRED").contains(status))return;
        long quantity=data.getLong("Quantity"),filled=data.getLong("TradedQty"),remaining=data.getLong("RemainingQuantity");
        double average=data.optDouble("AvgTradedPrice",0);
        if(quantity<=0 || filled<0 || remaining<0 || filled+remaining!=quantity || (filled>0 && (!Double.isFinite(average)||average<=0)))return;
        Instant time=timestamp(data.getString("LastUpdatedTime"));
        if(time.isAfter(clock.instant().plusSeconds(60)))return;
        String exchange=data.getString("Exchange").toUpperCase(Locale.ROOT),segment=data.getString("Segment").toUpperCase(Locale.ROOT);
        String mapped=switch(exchange+":"+segment){case "NSE:E"->"NSE_EQ";case "BSE:E"->"BSE_EQ";case "NSE:D"->"NSE_FNO";case "BSE:D"->"BSE_FNO";default->throw new IllegalArgumentException();};
        String side=switch(data.getString("TxnType")){case "B"->"BUY";case "S"->"SELL";default->throw new IllegalArgumentException();};
        JSONObject row=new JSONObject().put("dhanClientId",session.client).put("orderId",id).put("correlationId",correlation)
            .put("orderStatus",status).put("quantity",quantity).put("filledQty",filled).put("remainingQuantity",remaining)
            .put("averageTradedPrice",average).put("exchangeSegment",mapped).put("transactionType",side).put("securityId",data.getString("SecurityId"));
        synchronized(session) {
            if(!current(session,generation))return;
            Version previous=session.versions.get(id);
            if(previous!=null && (time.isBefore(previous.time()) || filled<previous.filled()
                || (terminal(previous.status()) && !previous.status().equals(status))
                || (time.equals(previous.time()) && filled==previous.filled() && status.equals(previous.status()))))return;
            if(session.versions.size()>=1000 && !session.versions.containsKey(id))session.versions.remove(session.versions.keySet().iterator().next());
            session.versions.put(id,new Version(time,filled,status));session.failures=0;session.lastPong=clock.instant();
        }
        var event=new DhanOrderUpdateEvent(session.id,id,correlation,row.toString());
        enqueue(()->{if(current(session,generation))events.publishEvent(event);});
    }
    private static boolean terminal(String status){return Set.of("TRADED","REJECTED","CANCELLED","EXPIRED").contains(status);}
    private static Instant timestamp(String value) {
        try{return OffsetDateTime.parse(value).toInstant();}catch(DateTimeParseException ignored){}
        var fmt=new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND,0,9,true).optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT);
        return LocalDateTime.parse(value,fmt).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
    @PreDestroy public synchronized void shutdown(){stopped=true;sessions.values().forEach(this::close);sessions.clear();delivery.shutdownNow();}
}
