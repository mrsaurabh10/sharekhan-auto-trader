package org.com.sharekhan.analysis;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.StockAnalysisService.*;

/** Page and scanner reads are served entirely from PostgreSQL, including stale/unavailable results. */
@Service
public class SavedDailyAnalysis {
    public static final String PROVIDER="Dhan · saved database";
    private final StockDailyHistoryStore store;
    private final SwingSessions calendar;
    public SavedDailyAnalysis(StockDailyHistoryStore store,SwingSessions calendar){this.store=store;this.calendar=calendar;}
    public List<StockDailyHistoryStore.Member> members(){return store.members();}
    public StockDailyHistoryStore.Saved read(String symbol,LocalDate day){return store.read(symbol,day);}
    public List<Instrument> search(String exchange,String query) {
        exchange=StockAnalysisService.exchange(exchange);String q=query==null?"":query.trim().toUpperCase(Locale.ROOT);
        if(!exchange.equals("NC")||q.length()<2)return List.of();if(q.length()>60)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Query too long");
        return members().stream().filter(m->m.symbol().contains(q)).sorted(Comparator.comparing((StockDailyHistoryStore.Member m)->!m.symbol().equals(q)).thenComparing(StockDailyHistoryStore.Member::symbol))
            .limit(25).map(m->new Instrument(null,m.symbol(),"NC")).toList();
    }
    public Report analyze(String exchange,String symbol,Instant now) {
        exchange=StockAnalysisService.exchange(exchange);String name=symbol==null?"":symbol.trim().toUpperCase(Locale.ROOT);
        if(!exchange.equals("NC")||members().stream().noneMatch(m->m.symbol().equals(name)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"No saved Nifty 200 daily dataset for this exchange/symbol.");
        LocalDate required=calendar.expected(LocalDateTime.ofInstant(now,IST));LocalDate through=required==null?LocalDateTime.ofInstant(now,IST).toLocalDate().minusDays(1):required;
        var saved=read(name,through);var notices=new ArrayList<String>();
        notices.add("Daily candles are read from PostgreSQL. Opening or refreshing this page makes no historical-data API request.");
        notices.add("Dhan security ID: "+(saved.securityId()==null?"unavailable":saved.securityId())+". It is not a Sharekhan scrip code. Corporate-action adjustment is unverified.");
        notices.add("Expected completed session: "+(required==null?"calendar update required":required)+"; stored through "+saved.latest()+". Last capture: "+saved.capturedAt()+".");
        boolean stale=required==null||!StockDailyHistoryStore.hasRequiredSessions(saved.candles().stream().map(c->c.date()).toList(),required,calendar);
        if(stale)notices.add("Saved data is not current for the expected completed session. New swing targets are withheld until the historical update succeeds.");
        var report=calculate(new Instrument(null,name,exchange),"SWING",saved.candles(),now,PROVIDER,notices,through);
        return stale&&!report.candles().isEmpty()?new Report(report.instrument(),report.mode(),report.interval(),report.provider(),report.timezone(),report.fetchedAt(),report.asOf(),"STALE",report.trend(),report.received(),report.rejected(),report.duplicates(),report.gaps(),report.candles(),report.indicators(),report.checks(),report.warnings()):report;
    }
    public record Status(String provider,LocalDate universeDate,int instruments,int current,LocalDate expectedSession,LocalDate oldestLatestSession,StockDailyHistoryStore.Update lastUpdate) { }
    public Status status(Instant now) {
        var members=members();var required=calendar.expected(LocalDateTime.ofInstant(now,IST));int fresh=0;LocalDate oldest=null;
        var dates=store.latestDates(required==null?LocalDateTime.ofInstant(now,IST).toLocalDate():required);var current=required==null?Set.<String>of():store.currentSymbols(required,calendar);
        for(var member:members){var latest=dates.get(member.symbol());if(current.contains(member.symbol()))fresh++;if(latest!=null&&(oldest==null||latest.isBefore(oldest)))oldest=latest;}
        return new Status(PROVIDER,members.isEmpty()?null:members.getFirst().snapshotDate(),members.size(),fresh,required,oldest,store.latestUpdate().orElse(null));
    }
}
