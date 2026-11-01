package org.com.sharekhan.analysis;

import jakarta.annotation.PreDestroy;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.com.sharekhan.analysis.SwingModels.*;

@Service
public class DailySwingService {
    private final SavedDailyAnalysis daily;
    private final SwingPlanner planner;
    private final SwingSessions calendar;
    private final SwingReportStore store;
    private final Clock clock;
    private List<String> universe(){return daily.members().stream().map(StockDailyHistoryStore.Member::symbol).toList();}
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"swing-research");t.setDaemon(true);return t;});
    private volatile boolean running;
    private volatile Instant lastAttempt;
    private volatile String problem;
    @org.springframework.beans.factory.annotation.Autowired
    public DailySwingService(SavedDailyAnalysis daily,SwingPlanner planner,SwingSessions calendar,SwingReportStore store){this(daily,planner,calendar,store,Clock.system(StockAnalysisService.IST));}
    DailySwingService(SavedDailyAnalysis daily,SwingPlanner planner,SwingSessions calendar,SwingReportStore store,Clock clock){this.daily=daily;this.planner=planner;this.calendar=calendar;this.store=store;this.clock=clock;}
    @EventListener(StockDailyHistoryUpdater.Updated.class) public void historyUpdated(){requestScan();}
    public record State(boolean running,Instant lastAttempt,String problem,LocalDate expectedDate,Report report,String entryWindow,
                        List<Observation> observations,List<String> universe,SavedDailyAnalysis.Status historyUpdate) { }
    public State state() {
        var now=LocalDateTime.now(clock);var day=calendar.expected(now);
        var report=day==null?Optional.<Report>empty():store.read(day);
        if(report.isEmpty())report=store.history().stream().findFirst();
        String window=report.isEmpty()||report.get().validSession()==null?"UNAVAILABLE":window(report.get().validSession(),now);
        return new State(running,lastAttempt,problem,day,report.orElse(null),window,store.observations(),universe(),daily.status(clock.instant()));
    }
    static String window(LocalDate valid,LocalDateTime now) {
        if(now.toLocalDate().isAfter(valid)||now.toLocalDate().equals(valid)&&!now.toLocalTime().isBefore(LocalTime.of(15,15)))return "EXPIRED";
        if(now.toLocalDate().isBefore(valid)||now.toLocalTime().isBefore(LocalTime.of(9,15)))return "UPCOMING";
        return "ENTRY_SESSION";
    }
    public List<Report> history(){return store.history();}
    public Optional<Report> report(LocalDate day){return store.read(day);}
    @EventListener(ApplicationReadyEvent.class)
    public void initial(){try{requestScan();}catch(Exception e){problem="Saved research is unavailable; check the persistent research directory.";}}
    // App-owned research schedule; broker login is never initiated here.
    @Scheduled(cron="0 15 19-23 * * *",zone="Asia/Kolkata")
    @Scheduled(cron="0 55 8 * * MON-FRI",zone="Asia/Kolkata")
    public void scheduled(){if(calendar.regular(LocalDate.now(clock)))requestScan();}
    public synchronized boolean requestScan() {
        Instant now=clock.instant();var day=calendar.expected(LocalDateTime.now(clock));
        if(day==null){problem="The swing calendar needs a verified update for this year.";return false;}
        if(running||lastAttempt!=null&&Duration.between(lastAttempt,now).toMinutes()<10)return false;
        var saved=store.read(day);
        if(saved.isPresent()&&!saved.get().status().equals("DATA_INCOMPLETE"))return false;
        running=true;lastAttempt=now;problem=null;
        worker.submit(()->{
            try{scan(day);}catch(Exception e){problem="Swing scan could not finish. Saved reports remain available; retry later.";}
            finally{running=false;}
        });
        return true;
    }
    void scan(LocalDate day) throws InterruptedException {
        LocalDate valid=calendar.next(day);var results=new ArrayList<StockResult>();
        var data=new HashMap<Integer,List<HistoricalCandle>>();
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8);
        for(String symbol:universe()) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(System.nanoTime()>deadline){results.add(new StockResult(symbol,0,"UNAVAILABLE","Scan time budget reached.",null,false,false,null));continue;}
            var input=daily.read(symbol,day);
            if(input.securityId()==null||!input.securityId().matches("[0-9]{1,9}")||input.tick()==null){results.add(new StockResult(symbol,0,"UNAVAILABLE","No saved verified daily identity/history.",null,false,false,null));continue;}
            // This research code is explicitly a Dhan security ID; it is never routed to Sharekhan or order services.
            var stock=ScriptMasterEntity.builder().scripCode(Integer.parseInt(input.securityId())).tradingSymbol(symbol).exchange("NC").tickSize(input.tick().doubleValue()).build();
            var candles=input.candles();
            data.put(stock.getScripCode(),candles);
            var result=planner.evaluate(stock,candles,day,valid,SavedDailyAnalysis.PROVIDER);
            if(!result.status().equals("UNAVAILABLE")&&!recentSessionsPresent(candles,day))result=new StockResult(symbol,stock.getScripCode(),"UNAVAILABLE","Missing a required recent regular session.",result.lastDate(),false,false,null);
            results.add(result);
        }
        store.save(assemble(day,valid,results,clock.instant()));
        // Only observed EOD levels, never fabricated executions. Recompute from immutable plans.
        var observations=new ArrayList<Observation>();
        for(var old:store.history().stream().limit(30).toList())for(var plan:old.plans()) {
            var source=data.get(plan.scripCode());
            if(source!=null)observations.add(planner.observe(plan,source,day));
        }
        store.saveObservations(observations);
    }
    private boolean recentSessionsPresent(List<HistoricalCandle> candles,LocalDate day) {
        var present=new HashSet<LocalDate>();for(var c:candles)if(c!=null)present.add(c.date());
        LocalDate d=day;int count=0;
        while(count<20&&calendar.supported(d)) {if(calendar.regular(d)){if(!present.contains(d))return false;count++;}d=d.minusDays(1);}
        return count==20;
    }
    static Report assemble(LocalDate day,LocalDate valid,List<StockResult> rows,Instant now) {
        var covered=rows.stream().filter(r->!r.status().equals("UNAVAILABLE")).toList();
        double breadth=covered.isEmpty()?0:covered.stream().filter(StockResult::above50).count()/(double)covered.size();
        String status=valid==null||covered.size()<Math.ceil(rows.size()*.8)||rows.isEmpty()?"DATA_INCOMPLETE":breadth<.5?"MARKET_CAUTION":"READY";
        var plans=status.equals("READY")?rows.stream().map(StockResult::plan).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Plan::riskPercent).thenComparing(Plan::symbol)).limit(10).toList():List.<Plan>of();
        if(status.equals("READY")&&plans.isEmpty())status="NO_SETUPS";
        var publishedRows=status.equals("READY")?List.copyOf(rows):rows.stream().map(r->r.plan()==null?r:
                new StockResult(r.symbol(),r.scripCode(),"GATE_BLOCKED","Candidate withheld by the report coverage/market gate.",r.lastDate(),r.above50(),r.above200(),null)).toList();
        return new Report("INDIA_SWING_SAVED_DHAN_V2",day,valid,now,status,rows.size(),covered.size(),breadth,plans,publishedRows,List.of(
                "Saved Nifty 200 snapshot; daily candles read from PostgreSQL. At least 80% coverage and 50% above SMA50 required.",
                "Unvalidated technical presets, not backtested recommendations or confidence probabilities. Review events and corporate actions independently.",
                "Targets are 2R/3R projections, sized from maximum entry. Entry validity is one regular session, then expires.",
                "EOD observations do not prove fills or intraday ordering. No portfolio holdings, charges, taxes or slippage are evaluated.",
                "Adapted skill methods: breakout-trade-planner (worst-entry risk/chase/R targets), position-sizer (whole-share/risk caps), backtest-expert (as-of and ambiguity controls). Full VCP screening is not implemented.",
                "Research scripCode identifiers in these Dhan plans are Dhan security IDs, not Sharekhan order identities.",
                "Regular-session calendar verified for 2026 only. Special-session entry is unsupported. No orders or external messages are sent."));
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
