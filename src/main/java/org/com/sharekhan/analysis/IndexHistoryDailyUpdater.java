package org.com.sharekhan.analysis;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;

/** Existing7pm index capture triggers this bounded worker; independent retries and restart catch-up. */
@Slf4j @Service
public class IndexHistoryDailyUpdater {
    private final IndexHistoryDailyStore store;private final DhanDailyHistoryClient client;private final IndexHistorySessions calendar;private final boolean enabled;private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"daily-index-minute-history");t.setDaemon(true);return t;});
    @Autowired public IndexHistoryDailyUpdater(IndexHistoryDailyStore store,DhanDailyHistoryClient client,IndexHistorySessions calendar,@Value("${app.index-history.enabled:${app.market-data.daily-history-enabled:true}}") boolean enabled){this(store,client,calendar,enabled,Clock.system(StockAnalysisService.IST));}
    IndexHistoryDailyUpdater(IndexHistoryDailyStore store,DhanDailyHistoryClient client,IndexHistorySessions calendar,boolean enabled,Clock clock){this.store=store;this.client=client;this.calendar=calendar;this.enabled=enabled;this.clock=clock;}
    @EventListener(ApplicationReadyEvent.class) public void startup(){request();}
    @Scheduled(cron="0 15 20-23 * * *",zone="Asia/Kolkata")
    @Scheduled(cron="0 50 8 * * *",zone="Asia/Kolkata") public void retry(){request();}
    public boolean request(){
        if(!enabled||!running.compareAndSet(false,true))return false;
        worker.submit(()->{try{update();}catch(Exception e){log.warn("Index history worker unavailable; existing candles retained ({}).",e.getClass().getSimpleName());}finally{running.set(false);}});return true;
    }
    void update(){
        if(!enabled)return;
        var through=calendar.through(LocalDateTime.now(clock));var jobs=new LinkedHashSet<IndexHistoryDailyStore.Job>();
        // Prefer recent sessions, persist the entire missing tail before a bounded batch can advance latest dates.
        for(int i=0;i<7;i++)for(String symbol:List.of("NIFTY","BANKNIFTY"))jobs.add(new IndexHistoryDailyStore.Job(symbol,through.minusDays(i)));
        jobs.addAll(store.pending(through));
        for(String symbol:List.of("NIFTY","BANKNIFTY")){
            LocalDate latest=store.latestCommon(symbol),from=latest==null?through.minusDays(7):latest;
            if(from.isBefore(through.minusYears(5)))from=through.minusYears(5);
            for(var d=from;!d.isAfter(through);d=d.plusDays(1))jobs.add(new IndexHistoryDailyStore.Job(symbol,d));
        }
        var missing=new ArrayList<IndexHistoryDailyStore.Job>();int skipped=0;
        for(var j:jobs){var w=calendar.window(j.day());if(w==null)continue;if(store.complete(j.symbol(),w)){store.markCurrent(j,w,clock.instant());skipped++;}else{store.plan(j);missing.add(j);}}
        int attempted=0,updated=0,failed=0;long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
        for(var j:missing){
            if(Thread.currentThread().isInterrupted())return;
            if(attempted>=20||System.nanoTime()>deadline)break;
            var window=calendar.window(j.day());
            if(j.day().atTime(window.end()).plusMinutes(30).isAfter(LocalDateTime.now(clock)))continue;
            var claim=clock.instant().truncatedTo(ChronoUnit.MICROS);if(!store.claim(j,claim))continue;attempted++;
            try{
                var cp=store.checkpoints(j);var w=calendar.window(j.day());
                String raw5=cp.fiveValid()?cp.five():client.fetchIndex(j.symbol(),j.day(),5);
                store.checkpoint(j,claim,5,raw5,false);var five=IndexIntradayBars.parse(raw5,5,w);store.checkpoint(j,claim,5,raw5,true);
                String raw1=cp.oneValid()?cp.one():client.fetchIndex(j.symbol(),j.day(),1);
                store.checkpoint(j,claim,1,raw1,false);var one=IndexIntradayBars.parse(raw1,1,w);store.checkpoint(j,claim,1,raw1,true);
                try{IndexIntradayBars.verifyPair(one,five);}catch(IllegalArgumentException disagreement){store.invalidatePair(j,claim);throw disagreement;}
                store.save(j,w,one,five,claim,clock.instant());updated++;
            }catch(DhanDailyHistoryClient.Unavailable e){failed++;store.fail(j,claim,clock.instant(),e.getMessage());log.warn("Index history retry needed: symbol={} session={} reason={}",j.symbol(),j.day(),e.getMessage());if(e.stopRun)break;}
            catch(InterruptedException e){Thread.currentThread().interrupt();store.fail(j,claim,clock.instant(),"Index history update interrupted; saved candles retained.");return;}
            catch(Exception e){failed++;store.fail(j,claim,clock.instant(),"Index session incomplete or failed validation/storage; saved candles retained.");log.warn("Index history retry needed: symbol={} session={} validation/storage={}",j.symbol(),j.day(),e.getClass().getSimpleName());}
        }
        log.info("INDEX_HISTORY_DAILY | through={} updated={} current={} failed={} remaining={} intervals=1,5",through,updated,skipped,failed,missing.size()-attempted);
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
