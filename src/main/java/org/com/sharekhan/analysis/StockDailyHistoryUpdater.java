package org.com.sharekhan.analysis;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded worker updates missing completed EOD sessions; HTTP reads never enter this path. */
@Service
public class StockDailyHistoryUpdater {
    private final StockDailyHistoryStore store;
    private final DhanDailyHistoryClient client;
    private final SwingSessions calendar;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"saved-stock-history-update");t.setDaemon(true);return t;});
    public record Updated(LocalDate session) { }
    @Autowired public StockDailyHistoryUpdater(StockDailyHistoryStore store,DhanDailyHistoryClient client,SwingSessions calendar,ApplicationEventPublisher events,@Value("${app.stock-history.enabled:true}") boolean enabled){this(store,client,calendar,events,enabled,Clock.system(StockAnalysisService.IST));}
    StockDailyHistoryUpdater(StockDailyHistoryStore store,DhanDailyHistoryClient client,SwingSessions calendar,ApplicationEventPublisher events,boolean enabled,Clock clock){this.store=store;this.client=client;this.calendar=calendar;this.events=events;this.enabled=enabled;this.clock=clock;}
    @EventListener(ApplicationReadyEvent.class) public void startup(){request();}
    @Scheduled(cron="0 0 19-23 * * *",zone="Asia/Kolkata")
    @Scheduled(cron="0 45 8 * * MON-FRI",zone="Asia/Kolkata")
    public void scheduled(){if(calendar.regular(LocalDate.now(clock)))request();}
    public boolean request() {
        if(!enabled||!running.compareAndSet(false,true))return false;
        worker.submit(()->{try{update();}finally{running.set(false);}});return true;
    }
    void update() {
        var day=calendar.expected(LocalDateTime.now(clock));if(day==null)return;
        var claim=clock.instant().truncatedTo(ChronoUnit.MICROS);boolean claimed=false;int updated=0,skipped=0,failed=0;String problem=null;
        try {
            var members=store.members();if(members.size()!=200)return; // require the verified complete imported universe
            var current=store.currentSymbols(day,calendar);
            if(members.stream().allMatch(m->current.contains(m.symbol())))return;
            if(!store.claim(day,claim))return;claimed=true;
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
            for(int i=0;i<members.size();i++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>deadline){failed+=members.size()-i;problem="Update time budget reached; remaining stocks will retry later.";break;}
                var member=members.get(i);var saved=store.read(member.symbol(),day);
                if(current.contains(member.symbol())){skipped++;continue;}
                // Seven calendar days of overlap permits recent provider corrections without downloading the whole archive.
                LocalDate from=saved.latest()==null?day.minusDays(450):saved.latest().minusDays(7);
                var present=new HashSet<LocalDate>();saved.candles().forEach(c->present.add(c.date()));
                int count=0;for(LocalDate d=day;count<20&&calendar.supported(d);d=d.minusDays(1))if(calendar.regular(d)){if(!present.contains(d)&&d.isBefore(from))from=d;count++;}
                if(from.isBefore(day.minusDays(450)))from=day.minusDays(450);
                try {
                    var input=client.fetch(member,saved,from,day);
                    var mergedDates=new HashSet<>(present);input.candles().forEach(c->mergedDates.add(c.date()));
                    if(!StockDailyHistoryStore.hasRequiredSessions(mergedDates,day,calendar))throw new IllegalArgumentException("Required recent sessions missing");
                    store.save(member,input.securityId(),input.tick(),from,day,input.candles(),clock.instant());updated++;
                }catch(DhanDailyHistoryClient.Unavailable e){failed++;problem=e.getMessage();if(e.stopRun){failed+=members.size()-i-1;break;}}
                catch(InterruptedException e){throw e;}
                catch(Exception e){failed++;problem="A historical update failed; validated saved data was retained.";}
            }
            store.finish(day,claim,clock.instant(),failed==0?"COMPLETE":"PARTIAL",updated,skipped,failed,problem);
            try{events.publishEvent(new Updated(day));}catch(RuntimeException ignored){/* The regular DB-only research schedule can retry report generation; history is already committed. */}
        }catch(InterruptedException e){Thread.currentThread().interrupt();if(claimed)store.finish(day,claim,clock.instant(),"PARTIAL",updated,skipped,Math.max(1,failed),"Historical update interrupted; saved history retained.");}
        catch(Exception e){if(claimed)store.finish(day,claim,clock.instant(),"FAILED",updated,skipped,Math.max(1,failed),"Historical storage/update unavailable; saved history retained.");}
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
