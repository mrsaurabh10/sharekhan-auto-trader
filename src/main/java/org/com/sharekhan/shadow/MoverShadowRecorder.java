package org.com.sharekhan.shadow;

import jakarta.annotation.*;
import org.com.sharekhan.cache.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.NseMarketCalendar;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import static org.com.sharekhan.shadow.ShadowModels.*;

/** Nonblocking producer, one bounded research writer. Deliberately has no order or broker client dependency. */
@Component
public class MoverShadowRecorder {
    private static final Logger log=LoggerFactory.getLogger(MoverShadowRecorder.class);
    public static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private final MoverShadowStore store;
    private final QuoteCacheService quotes;
    private final LtpCacheService prices;
    private final NseMarketCalendar calendar;
    private final WebSocketSubscriptionService feeds;
    private final ArrayBlockingQueue<Decision> queue=new ArrayBlockingQueue<>(1024);
    private final Map<String,Watch> watches=new LinkedHashMap<>();
    private final AtomicLong dropped=new AtomicLong();
    private final AtomicLong failures=new AtomicLong();
    private final Map<String,String> gaps=new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mover-shadow-writer");t.setDaemon(true);return t;});
    @Value("${app.strategy.mover-shadow.enabled:false}") private boolean enabled;
    @Value("${app.strategy.mover-shadow.sample-ms:5000}") private long sampleMillis=5000;
    @Value("${app.strategy.mover-shadow.max-rows-per-day:50000}") private long maxRows=50000;
    @Value("${app.strategy.mover-shadow.daily-bytes:33554432}") private long dailyLimit=33554432;
    @Value("${app.strategy.mover-shadow.total-bytes:262144000}") private long totalLimit=262144000;
    private volatile String state="DISABLED";
    private LocalDate day;
    private long rows,bytes,lastSample,lastHealth;
    private boolean capacityAvailable=true;
    public MoverShadowRecorder(MoverShadowStore store,QuoteCacheService quotes,LtpCacheService prices,
                               NseMarketCalendar calendar,WebSocketSubscriptionService feeds) {
        this.store=store;this.quotes=quotes;this.prices=prices;this.calendar=calendar;this.feeds=feeds;
    }
    @PostConstruct public void start() {worker.scheduleWithFixedDelay(this::tickSafely,1,1,TimeUnit.SECONDS);}
    @PreDestroy public void stop() {
        worker.shutdownNow();
        try { if(worker.awaitTermination(2,TimeUnit.SECONDS))release(); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    public boolean enabled(){return enabled;}
    public Map<String,Object> health() {
        return Map.of("enabled",enabled,"state",state,"dropped",dropped.get(),"writerFailures",failures.get(),
            "queueSize",queue.size(),"gaps",Map.copyOf(gaps),"sampleMillis",Math.max(5000,sampleMillis),
            "maxRowsPerDay",maxRows,"dailyBytes",dailyLimit,"totalBytes",totalLimit);
    }
    public Sample snapshot(int code,int spotCode,Instant at) {
        var q=quotes.getSnapshot(code).orElse(null);
        boolean sameSource=q!=null&&Objects.equals(q.getBookSource(),q.getLtpSource())&&Objects.equals(q.getBookExchange(),q.getLtpExchange())&&Objects.equals(q.getBookProviderIdentity(),q.getLtpProviderIdentity());
        Book book=q==null?null:new Book(q.getBestBid(),q.getBestAsk(),sameSource?q.getLastTradedPrice():null,q.getLastBookAt(),q.getLastLtpAt(),
            q.getBookSource(),q.getBookExchange(),q.getBookProviderIdentity(),q.getBidQuantity(),q.getAskQuantity(),q.isCoherentBook());
        var minute=prices.getLastCompletedMinuteCandle(spotCode);
        var observed=prices.getObservedAt(spotCode);
        return new Sample(at,book,prices.getLtp(spotCode),observed==null?null:observed.atZone(ZONE).toInstant(),
            minute==null?null:minute.minute(),minute==null?null:minute.close());
    }
    /** No serialization, database access, subscription or quote request on the order thread. */
    public void capture(TriggeredTradeSetupEntity t, double soft,double hard,double age,double ceiling,
                        boolean fresh,boolean window,List<String> signals) {
        if (!enabled || t==null || !"strategy:FNO_0925_MOVER_ATR_BREAKOUT".equals(t.getSource())
                || t.getScripCode()==null || t.getSpotScripCode()==null) return;
        try {
            Instant now=Instant.now(); LocalDate date=now.atZone(ZONE).toLocalDate();
            int lots=t.getLots()==null?1:t.getLots();long qty=t.getQuantity()==null?0:t.getQuantity();
            String key=t.getExchange()+":"+t.getScripCode()+":"+t.getExpiry()+":"+t.getStrikePrice()+":"+t.getOptionType();
            String setup=date+":"+MoverShadowSignalContext.current()+":"+t.getSymbol()+":"+t.getOptionType()+":"+t.getEntryPrice()+":"+t.getStopLoss()+":"+t.getTarget1();
            var d=new Decision(UUID.randomUUID().toString(),date,now,t.getAppUserId(),t.getBrokerCredentialsId(),t.getTriggerRequestId(),
                setup,key,t.getSymbol(),t.getExchange(),t.getScripCode(),t.getSpotScripCode(),t.getExpiry(),t.getStrikePrice(),t.getOptionType(),
                lots,qty,t.getEntryPrice(),t.getStopLoss(),Arrays.asList(t.getTarget1(),t.getTarget2(),t.getTarget3()),soft,hard,age,ceiling,fresh,window,
                snapshot(t.getScripCode(),t.getSpotScripCode(),now),MoverShadowSignalContext.current(),"FNO_MOVER_SHADOW_V1",signals==null?List.of():List.copyOf(signals));
            offer(d);
        } catch(Exception e) {dropped.incrementAndGet();}
    }
    boolean offer(Decision d) {if(!queue.offer(d)){dropped.incrementAndGet();return false;}return true;}
    private void tickSafely() {
        if(!enabled)return;
        try {tick(Instant.now());}catch(Exception e){state="DEGRADED";failures.incrementAndGet();log.warn("Mover shadow observer unavailable: {}",e.getClass().getSimpleName());}
    }
    void tick(Instant at) throws Exception {
        var local=at.atZone(ZONE);
        if(!Objects.equals(day,local.toLocalDate())) {
            release();day=local.toLocalDate();gaps.clear();dropped.set(0);failures.set(0);
            long[] usage=store.dailyUsage(day);rows=usage[0];bytes=usage[1];
            for(var e:store.events(day,"WATCH")) {var w=store.read(e.payload(),Watch.class);if(watches.size()<10)watches.put(w.key(),w);}
        }
        if(lastHealth==0 || at.toEpochMilli()-lastHealth>=30000) {
            capacityAvailable=store.bytes()<Math.max(1,totalLimit) && Files.getFileStore(Path.of(".")).getUsableSpace()>512L*1024*1024;
            lastHealth=at.toEpochMilli();store.saveReport(day,0L,store.json(health()));
        }
        if(!capacityAvailable || rows>=maxRows || bytes>=dailyLimit) {
            state="QUOTA_STOP";dropped.addAndGet(queue.size());queue.clear();release();return;
        }
        List<Decision> decisions=new ArrayList<>();queue.drainTo(decisions,100);
        List<Event> events=new ArrayList<>();
        for(var d:decisions) {
            if(!d.day().equals(day)){dropped.incrementAndGet();continue;}
            events.add(event(d.id(),d.contractKey(),"DECISION",d.userId(),d.at(),d));
            if(!watches.containsKey(d.contractKey())) {
                Set<Integer> spots=new HashSet<>();watches.values().forEach(w->spots.add(w.spotCode()));
                if(watches.size()>=10 || (!spots.contains(d.spotCode()) && spots.size()>=5)) {gaps.put(d.contractKey(),"WATCH_LIMIT");continue;}
                var w=new Watch(d.contractKey(),day,d.exchange(),d.code(),d.spotCode());watches.put(w.key(),w);
                events.add(event(UUID.randomUUID().toString(),w.key(),"WATCH",null,at,w));
            }
        }
        if(calendar.isTradingDay(day) && !local.toLocalTime().isBefore(LocalTime.of(9,25)) && local.toLocalTime().isBefore(LocalTime.of(15,36))) {
            state="RECORDING";
            if(at.toEpochMilli()-lastSample>=Math.max(5000,sampleMillis)) {
                lastSample=at.toEpochMilli();
                for(var w:watches.values()) {
                    feeds.retainObserverFeed("mover-shadow-option:"+w.key(),w.exchange()+w.code(),true);
                    feeds.retainObserverFeed("mover-shadow-spot:"+w.spotCode(),"NC"+w.spotCode(),false);
                    var sample=snapshot(w.code(),w.spotCode(),at);String json=store.json(sample);
                    // Include observation time so unchanged fresh books still establish coverage.
                    events.add(new Event(UUID.randomUUID().toString(),day,w.key(),"SAMPLE",null,at,json));
                    if(sample.book()==null || sample.book().bookAt()==null || sample.book().bookAt().plusSeconds(5).isBefore(at))gaps.put(w.key(),"MISSING_OR_STALE_BOOK");
                    else gaps.remove(w.key());
                }
            }
        } else {state="OUTSIDE_SESSION";release();}
        List<Event> bounded=new ArrayList<>();
        for(var e:events) {
            long length=e.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(rows+bounded.size()>=maxRows || bytes+length>dailyLimit){dropped.incrementAndGet();continue;}
            bounded.add(e);bytes+=length;
        }
        if(!bounded.isEmpty()){store.append(bounded);rows+=bounded.size();}
    }
    private Event event(String id,String key,String type,Long user,Instant at,Object payload){return new Event(id,day,key,type,user,at,store.json(payload));}
    private void release(){
        for(var w:watches.values()) {
            feeds.releaseObserverFeed("mover-shadow-option:"+w.key());
            feeds.releaseObserverFeed("mover-shadow-spot:"+w.spotCode());
        }
        watches.clear();
    }
}
