package org.com.sharekhan.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSubscriptionHelper implements WebSocketSubscriptionService {

    // Reference counts per feed mode + scrip key (e.g., full|NF42120, ltp|NC20000).
    private final ConcurrentHashMap<String, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    private final WebSocketConnector connector;
    private final java.util.Map<String,String> liveLtpOwners=new ConcurrentHashMap<>();
    private final Set<String> sentFeeds=ConcurrentHashMap.newKeySet();
    private final java.util.Map<String,java.time.Instant> lastLtpTicks=new ConcurrentHashMap<>();
    private volatile boolean feedConnectionReady=true;

    @Override public synchronized boolean retainLtpFeed(String owner,String key) {
        if(owner==null||owner.isBlank()||key==null||!key.matches("[A-Z]{2}[0-9]+"))throw new IllegalArgumentException("Feed owner and exact feed key are required");
        if(liveLtpOwners.size()>=2048&&!liveLtpOwners.containsKey(owner))return false;
        String previous=liveLtpOwners.put(owner,key);
        requestIfNeeded("ltp",key);
        if(previous!=null&&!previous.equals(key))releaseUnusedLtp(previous);
        return true;
    }
    @Override public synchronized void releaseLtpFeed(String owner) {
        String key=liveLtpOwners.remove(owner);if(key!=null)releaseUnusedLtp(key);
    }
    private void releaseUnusedLtp(String key) {
        if(!liveLtpOwners.containsValue(key)&&!refCounts.containsKey(refKey("ltp",key)))sendUnsubscribe("ltp",key);
    }
    public synchronized void connectionLost(){feedConnectionReady=false;sentFeeds.clear();lastLtpTicks.clear();}
    @Override public synchronized void restoreOwnedLtpFeeds() {
        feedConnectionReady=true;
        for(String key:new java.util.HashSet<>(liveLtpOwners.values()))requestIfNeeded("ltp",key);
    }
    public synchronized void retryOwnedLtpFeeds() {
        for(String key:new java.util.HashSet<>(liveLtpOwners.values()))requestIfNeeded("ltp",key);
    }
    public void recordLtpReceived(String key,java.time.Instant receivedAt) {
        if(receivedAt!=null&&(liveLtpOwners.containsValue(key)||refCounts.containsKey(refKey("ltp",key))||refCounts.containsKey(refKey("full",key))))
            lastLtpTicks.put(key,receivedAt);
    }
    @Override public synchronized java.util.Map<String,Object> ltpFeedStatus(String key) {
        var now=java.time.Instant.now();var tick=lastLtpTicks.get(key);
        boolean requested=liveLtpOwners.containsValue(key)||refCounts.containsKey(refKey("ltp",key));
        boolean connected=feedConnectionReady&&connector.isConnected();
        boolean sent=connected&&sentFeeds.contains(refKey("ltp",key));
        boolean receiving=sent&&tick!=null&&!tick.isAfter(now)&&!tick.isBefore(now.minusSeconds(5));
        var status=new java.util.LinkedHashMap<String,Object>();
        status.put("key",key);status.put("owners",liveLtpOwners.entrySet().stream().filter(e->e.getValue().equals(key)).map(java.util.Map.Entry::getKey).sorted().toList());
        status.put("requested",requested);status.put("connected",connected);status.put("sentOnConnection",sent);
        status.put("lastTickAt",tick);status.put("receivingFreshTicks",receiving);
        status.put("state",!requested?"NOT_REQUESTED":!connected?"DISCONNECTED":!sent?"PENDING_SUBSCRIBE":receiving?"RECEIVING":tick==null?"WAITING_FOR_TICKS":"STALE");
        return status;
    }
    private boolean requestIfNeeded(String mode,String key) {
        String ref=refKey(mode,key);
        if(!feedConnectionReady||sentFeeds.contains(ref)||!connector.isConnected())return false;
        boolean sent=connector.trySend("{\"action\":\"feed\",\"key\":[\""+mode+"\"],\"value\":[\""+key+"\"]}");
        if(sent)sentFeeds.add(ref);return sent;
    }
    private boolean sendUnsubscribe(String mode,String key) {
        if(!sentFeeds.remove(refKey(mode,key)))return false;
        if("ltp".equals(mode))lastLtpTicks.remove(key);
        return connector.trySend("{\"action\":\"unsubscribe\",\"key\":[\""+mode+"\"],\"value\":[\""+key+"\"]}");
    }


    private final java.util.Map<String,String> observerRefs = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public synchronized boolean retainObserverFeed(String owner,String key,boolean full) {
        if(observerRefs.containsKey(owner)) return true;
        if(!getActiveScripKeys().contains(key)) return false;
        String mode=full?"full":"ltp";
        subscribe(key,mode);observerRefs.put(owner,mode+"|"+key);return true;
    }
    @Override public synchronized void releaseObserverFeed(String owner) {
        String ref=observerRefs.remove(owner);
        if(ref!=null)unsubscribe(scripKey(ref),ref.substring(0,ref.indexOf('|')));
    }
    @Override public java.util.Set<String> getObserverOnlyScripKeys() {
        java.util.Map<String,Integer> owned=new java.util.HashMap<>();
        observerRefs.values().forEach(ref->owned.merge(scripKey(ref),1,Integer::sum));
        java.util.Map<String,Integer> all=new java.util.HashMap<>();
        refCounts.forEach((ref,count)->all.merge(scripKey(ref),count.get(),Integer::sum));
        liveLtpOwners.values().forEach(key->all.merge(key,1,Integer::sum));
        java.util.Set<String> result=new java.util.HashSet<>();
        owned.forEach((key,count)->{if(all.getOrDefault(key,0)>0&&all.getOrDefault(key,0)<=count)result.add(key);});
        return result;
    }
    public synchronized void reset() {
        observerRefs.clear();
        log.info("🧹 Resetting WebSocket subscription reference counts");
        refCounts.clear();
        connectionLost();
    }

    @Override
    public boolean subscribeToScrip(String scripKey) {
        return subscribe(scripKey, "full");
    }

    @Override public boolean subscribeToScripDepth(String key){return subscribe(key,"depth");}
    @Override public boolean unsubscribeFromScripDepth(String key){return unsubscribe(key,"depth");}
    @Override public boolean hasDepthFeed(String key){return refCounts.containsKey(refKey("depth",key));}

    @Override
    public boolean subscribeToScripLtp(String scripKey) {
        return subscribe(scripKey, "ltp");
    }

    private synchronized boolean subscribe(String scripKey, String feedMode) {
        String refKey = refKey(feedMode, scripKey);
        refCounts.compute(refKey, (k, v) -> {
            if (v == null) {
                return new AtomicInteger(1);
            }
            v.incrementAndGet();
            return v;
        });

        return requestIfNeeded(feedMode,scripKey);
    }

    @Override
    public void subscribeToAck(String customerId) {
        connector.send("{\"action\":\"ack\",\"key\":[\"\"],\"value\":[\"" + customerId + "\"]}");
        log.info("📡 Subscribing Ack to {}", customerId);
    }

    @Override
    public boolean unsubscribeFromScrip(String scripKey) {
        return unsubscribe(scripKey, "full");
    }

    @Override
    public boolean unsubscribeFromScripLtp(String scripKey) {
        return unsubscribe(scripKey, "ltp");
    }

    private synchronized boolean unsubscribe(String scripKey, String feedMode) {
        String refKey = refKey(feedMode, scripKey);
        final boolean[] shouldUnsub = {false};
        refCounts.compute(refKey, (k, v) -> {
            if (v == null) {
                // nothing to do
                return null;
            }
            int n = v.decrementAndGet();
            if (n <= 0) {
                shouldUnsub[0] = true;
                return null; // remove entry
            }
            return v;
        });

        if(shouldUnsub[0] && !("ltp".equals(feedMode)&&liveLtpOwners.containsValue(scripKey)))
            return sendUnsubscribe(feedMode,scripKey);
        return false;
    }

    @Override
    public Set<String> getActiveScripKeys() {
        var keys=refCounts.keySet().stream().map(WebSocketSubscriptionHelper::scripKey).collect(Collectors.toSet());
        keys.addAll(liveLtpOwners.values());return keys;
    }

    private static String refKey(String feedMode, String scripKey) {
        return feedMode + "|" + scripKey;
    }

    private static String scripKey(String refKey) {
        int separator = refKey.indexOf('|');
        return separator >= 0 ? refKey.substring(separator + 1) : refKey;
    }
}
