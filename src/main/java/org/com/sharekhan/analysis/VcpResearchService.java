package org.com.sharekhan.analysis;

import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.SwingModels.Plan;

/** Native NSE VCP research. Only saved market inputs; independent data and entry gates. */
@Service
public class VcpResearchService {
    public static final String VERSION="NSE_VCP_CONFIRMED_SWINGS_V1";
    private final SavedDailyAnalysis daily;private final NiftyDailyBenchmark benchmark;private final SwingSessions calendar;private final Clock clock;
    private Report cached;
    public record Check(String name,boolean passed) { }
    public record Row(String symbol,String status,String phase,String reason,LocalDate lastDate,boolean validPattern,
                      List<VcpPattern.Contraction> contractions,Double pivot,Double pivotDistancePercent,
                      Double volumeDryUp,Double breakoutVolumeRatio,Double weightedRelativeStrength,Double rsPercentile,
                      int trendPassed,List<Check> trendChecks,Plan plan) { }
    public record Report(String version,LocalDate dataDate,LocalDate validSession,Instant generatedAt,String status,
                         int universeCount,int coveredCount,double breadthAbove50,int rsPopulation,int entryCandidatesBeforeMarketGate,
                         String benchmarkName,String benchmarkProvider,LocalDate benchmarkFirst,LocalDate benchmarkLast,
                         int benchmarkSessions,int rejectedBenchmarkGroups,List<Row> stocks,List<String> rules) { }
    record Input(StockDailyHistoryStore.Saved saved,List<HistoricalCandle> bars,String problem) { }
    @org.springframework.beans.factory.annotation.Autowired
    public VcpResearchService(SavedDailyAnalysis daily,NiftyDailyBenchmark benchmark,SwingSessions calendar){this(daily,benchmark,calendar,Clock.system(StockAnalysisService.IST));}
    VcpResearchService(SavedDailyAnalysis daily,NiftyDailyBenchmark benchmark,SwingSessions calendar,Clock clock){this.daily=daily;this.benchmark=benchmark;this.calendar=calendar;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public synchronized Report report() {
        var now=clock.instant();var day=calendar.expected(LocalDateTime.now(clock));
        if(cached!=null&&Objects.equals(cached.dataDate(),day)&&Duration.between(cached.generatedAt(),now).getSeconds()<60)return cached;
        var members=daily.members();var inputs=new LinkedHashMap<String,Input>();var references=benchmark.read(day);var valid=day==null?null:calendar.next(day);
        var byId=new HashMap<String,Set<String>>();
        for(var member:members){var saved=day==null?new StockDailyHistoryStore.Saved(member.symbol(),null,null,null,List.of()):daily.read(member.symbol(),day);var input=validate(saved,day);inputs.put(member.symbol(),input);if(saved.securityId()!=null)byId.computeIfAbsent(saved.securityId(),k->new HashSet<>()).add(member.symbol());}
        for(var group:byId.values())if(group.size()>1)for(String symbol:group){var i=inputs.get(symbol);inputs.put(symbol,new Input(i.saved(),List.of(),"Conflicting archived identity across stocks."));}
        var rs=new HashMap<String,Double>();int above50=0,covered=0;
        for(var e:inputs.entrySet())if(e.getValue().problem()==null){covered++;var bars=e.getValue().bars();if(bars.getLast().close()>sma(bars,50,0))above50++;Double value=relativeStrength(bars,references.closes());if(value!=null)rs.put(e.getKey(),value);}
        double breadth=covered==0?0:above50/(double)covered;var ranks=percentiles(rs);
        String gate=day==null||valid==null||members.size()!=200||covered<160?"DATA_INCOMPLETE":rs.size()<160||!references.closes().containsKey(day)?"BENCHMARK_INCOMPLETE":breadth<.5?"MARKET_CAUTION":"READY";
        var rows=new ArrayList<Row>();int candidates=0;
        for(var e:inputs.entrySet()) {
            var i=e.getValue();Row r=i.problem()==null?evaluate(i,day,valid,rs.get(e.getKey()),ranks.get(e.getKey())):unavailable(e.getKey(),i.problem(),i.bars().isEmpty()?null:i.bars().getLast().date());
            if(r.plan()!=null)candidates++;
            if(r.plan()!=null&&!gate.equals("READY"))r=withheld(r,gate);rows.add(r);
        }
        rows.sort(Comparator.comparing(Row::validPattern).reversed().thenComparing(Comparator.comparingInt(Row::trendPassed).reversed()).thenComparing(Row::symbol));
        var dates=new TreeSet<>(references.closes().keySet());
        cached=new Report(VERSION,day,valid,now,gate,members.size(),covered,breadth,rs.size(),candidates,references.symbol(),references.provider(),dates.isEmpty()?null:dates.first(),dates.isEmpty()?null:dates.last(),dates.size(),references.rejectedSessions(),List.copyOf(rows),List.of(
            "NSE cash VCP adaptation using saved Dhan stocks and NIFTY 50 price index (Dhan IDX_I security 13). No live quote, broker history request or order.",
            "Benchmark closes use complete 375-bar 09:15–15:29 IST native-minute sessions with accepted quality flags. The15:29minute close is a derived reference, not the official exchange EOD close. Stock daily and index minute-close bases can differ. Reviewed/partial sessions and mixed-provider substitutions are excluded.",
            "Relative strength: same-date stock minus NIFTY returns at 63/126/189/252 stock sessions, weights 40/20/20/20. All four horizons required; missing dates stay unavailable. Percentile is within the displayed eligible Nifty 200 sample, not a vendor or all-market RS rating.",
            "At least 253 valid daily bars and recent 20 sessions. Current constituent snapshot, unknown corporate-action adjustment and older-calendar limits remain; 252-session highs/lows are proxies for 52-week extremes.",
            "Trend checks: price above SMA150/SMA200; SMA150>SMA200; rising SMA200 over22bars; price>SMA50; ≥25% above252-session low; within25% of high; sample RS percentile>70. Six checks for developing trend, all seven for entry.",
            "120-bar confirmed1.5ATR reversal swings; up to4 contractions, each high-to-low duration≥5sessions. Top3 highs tested; no fixed-window fallback. Need≥2 contractions, first depth10–35%, each depth≤70% of previous, shoulders within5% of first high, totalspan≥15sessions, finaldepth≤10%, lastlow≤15sessions old.",
            "Pre-breakout: close within5% below pivot, prior10-bar/50-bar volume≤0.7. Breakout: close≤2% above pivot and volume≥1.5×prior50bars; dry-up excludes breakout bar. ₹20crore average turnover positive weighted relative strength and bullish last close required for entry plans.",
            "Entry pivot+max(tick,0.1ATR), maximum entry+0.25ATR, stop last contraction low−0.25ATR. Worst-entry risk≤8%; maximum≤pivot×1.02. T1/T2 project2R/3R sell exits, not forecasts. Tick/ID remain provider-specific.",
            "All entry plans require200-stock universe,≥80% data/RS coverage,≥50% aboveSMA50 and next verified regular session. Watch evidence remains visible when blocked. No short entries or holdings-linked exit advice.",
            "Entry valid next regular session09:15–15:15IST; skip gaps above maximum or stop breach before entry. Review after10sessions. Earnings/news/portfolio/costs remain separate checks. This variant is not an exact upstream reproduction or a validated trading edge."));
        return cached;
    }
    Input validate(StockDailyHistoryStore.Saved saved,LocalDate day) {
        var sorted=new TreeMap<LocalDate,HistoricalCandle>();String problem=null;
        if(day==null)return new Input(saved,List.of(),"Verified session calendar unavailable.");
        if(saved.securityId()==null||!saved.securityId().matches("[0-9]{1,9}")||saved.tick()==null||saved.tick().doubleValue()<.01||saved.tick().doubleValue()>5)return new Input(saved,List.of(),"Missing saved cash identity or supported tick.");
        for(var c:saved.candles()) {
            if(c==null||c.date()==null)return new Input(saved,List.of(),"Invalid daily date.");
            if(c.date().isAfter(day))continue;
            if(calendar.supported(c.date())&&!calendar.regular(c.date())&&!c.date().equals(LocalDate.of(2026,2,1)))problem="Nonregular historical session requires review.";
            var previous=sorted.putIfAbsent(c.date(),c);
            if(!StockAnalysisService.valid(c)||c.volume()==null||c.volume()<0||previous!=null&&!previous.equals(c))problem="Invalid OHLCV or conflicting daily dates.";
        }
        var bars=List.copyOf(sorted.values());
        if(problem!=null)return new Input(saved,bars,problem);
        if(bars.size()<253||!day.equals(bars.getLast().date()))return new Input(saved,bars,"Requires 253 bars and the expected completed session.");
        for(int n=1;n<bars.size();n++)if(Math.abs(bars.get(n).close()/bars.get(n-1).close()-1)>.30)return new Input(saved,bars,"Price discontinuity requires corporate-action review.");
        int found=0;for(LocalDate d=day;found<20&&calendar.supported(d);d=d.minusDays(1))if(calendar.regular(d)||d.equals(LocalDate.of(2026,2,1))){var c=sorted.get(d);if(c==null||c.volume()<=0)return new Input(saved,bars,"Missing recent session or positive recent volume.");found++;}
        return new Input(saved,bars,found<20?"Recent calendar unavailable.":null);
    }
    static Double relativeStrength(List<HistoricalCandle> bars,Map<LocalDate,Double> benchmark) {
        if(bars.size()<253)return null;var last=bars.getLast();Double end=benchmark.get(last.date());if(end==null||!Double.isFinite(end)||end<=0)return null;
        int[] horizons={63,126,189,252};double[] weights={.4,.2,.2,.2};double weighted=0;
        for(int n=0;n<4;n++){var earlier=bars.get(bars.size()-1-horizons[n]);Double start=benchmark.get(earlier.date());if(start==null||!Double.isFinite(start)||start<=0)return null;weighted+=weights[n]*100*((last.close()/earlier.close()-1)-(end/start-1));}
        return Double.isFinite(weighted)?weighted:null;
    }
    static Map<String,Double> percentiles(Map<String,Double> values) {
        var sorted=values.values().stream().sorted().toList();var result=new HashMap<String,Double>();
        values.forEach((symbol,value)->{long lower=sorted.stream().filter(v->v<value).count(),equal=sorted.stream().filter(v->v.equals(value)).count();result.put(symbol,sorted.size()<2?50:100*(lower+(equal-1)/2.0)/(sorted.size()-1));});return result;
    }
    Row evaluate(Input input,LocalDate day,LocalDate valid,Double rs,Double rank) {
        var bars=input.bars();var last=bars.getLast();String symbol=input.saved().symbol();var pattern=VcpPattern.detect(bars);double s50=sma(bars,50,0),s150=sma(bars,150,0),s200=sma(bars,200,0);
        var year=bars.subList(bars.size()-252,bars.size());double low=year.stream().mapToDouble(HistoricalCandle::low).min().orElseThrow(),high=year.stream().mapToDouble(HistoricalCandle::high).max().orElseThrow();
        var checks=List.of(new Check("Price above SMA150 and SMA200",last.close()>s150&&last.close()>s200),new Check("SMA150 above SMA200",s150>s200),new Check("SMA200 rising over22sessions",s200>sma(bars,200,22)),new Check("Price above SMA50",last.close()>s50),new Check("At least25% above252-session low",last.close()>=low*1.25),new Check("Within25% of252-session high",last.close()>=high*.75),new Check("Sample RS percentile above70",rank!=null&&rank>70));
        int trend=(int)checks.stream().filter(Check::passed).count();double average50=bars.subList(bars.size()-51,bars.size()-1).stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow();
        double recent10=bars.subList(bars.size()-11,bars.size()-1).stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow();Double dry=average50>0?recent10/average50:null,vol=average50>0?last.volume()/average50:null;
        Double pivot=pattern.pivot(),distance=pivot==null?null:100*(last.close()/pivot-1);String phase="DEVELOPING";
        if(pattern.valid())phase=last.close()<pattern.contractions().getLast().low()?"DAMAGED":distance>2?"EXTENDED":distance>0?(vol!=null&&vol>=1.5?"BREAKOUT":"BREAKOUT_UNCONFIRMED"):distance>=-5?"PRE_BREAKOUT":"BELOW_PIVOT";
        String reason=pattern.issues().isEmpty()?"Confirmed contracting price structure.":String.join(" ",pattern.issues());
        if(trend<7)reason+=" Trend checks "+trend+"/7; entry requires all seven.";
        if(rs==null)reason+=" Relative strength unavailable: missing same-date benchmark reference.";
        double turnover=bars.subList(bars.size()-20,bars.size()).stream().mapToDouble(c->c.close()*c.volume()).average().orElseThrow();
        boolean actionable=pattern.valid()&&trend==7&&rs!=null&&rs>0&&dry!=null&&dry<=.7&&Set.of("PRE_BREAKOUT","BREAKOUT").contains(phase)&&turnover>=200_000_000&&last.close()>last.open()&&valid!=null;
        Plan plan=null;
        if(actionable){double atr=VcpPattern.atr(bars,14),tick=input.saved().tick().doubleValue();double entry=SwingPlanner.round(pivot+Math.max(tick,.1*atr),tick,true),max=SwingPlanner.round(entry+.25*atr,tick,true),stop=SwingPlanner.round(pattern.contractions().getLast().low()-.25*atr,tick,false),risk=max-stop;
            if(Double.isFinite(atr)&&atr>0&&stop>0&&risk>0&&risk/max<=.08&&max<=pivot*1.02&&last.close()<=max){var indicators=bars.stream().map(c->new StockAnalysisService.Bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList();
                plan=new Plan(symbol,Integer.parseInt(input.saved().securityId()),"NC","NSE_VCP",SavedDailyAnalysis.PROVIDER,day,valid,entry,max,stop,SwingPlanner.round(max+2*risk,tick,true),SwingPlanner.round(max+3*risk,tick,true),risk,100*risk/max,atr,StockAnalysisService.rsi(indicators,14),turnover,"NSE confirmed-swing VCP with aligned NIFTY relative strength. Targets are projections, not forecasts.",List.of("Entry only "+valid+" 09:15–15:15IST; skip gaps above maximum entry.","Cancel on stop breach before entry. Review earnings/news and portfolio first.","Review holding after10completed sessions; no orders placed."));
            }else reason+=" Entry risk or2%chase ceiling failed.";
        } else if(pattern.valid())reason+=" Entry also requires positive relative performance, volume dry-up≤0.7, ₹20crore turnover and bullish close near pivot.";
        String status=plan!=null?"CONDITIONAL_BUY":pattern.valid()||trend>=6&&!pattern.contractions().isEmpty()?"WATCH_ONLY":"FILTERED";
        return new Row(symbol,status,phase,reason,last.date(),pattern.valid(),pattern.contractions(),pivot,distance,dry,vol,rs,rank,trend,checks,plan);
    }
    private static double sma(List<HistoricalCandle> bars,int period,int offset){return bars.subList(bars.size()-offset-period,bars.size()-offset).stream().mapToDouble(HistoricalCandle::close).average().orElseThrow();}
    private static Row unavailable(String symbol,String reason,LocalDate date){return new Row(symbol,"UNAVAILABLE","UNAVAILABLE",reason,date,false,List.of(),null,null,null,null,null,null,0,List.of(),null);}
    private static Row withheld(Row r,String gate){return new Row(r.symbol(),"WATCH_ONLY",r.phase(),r.reason()+" Entry levels withheld by "+gate+".",r.lastDate(),r.validPattern(),r.contractions(),r.pivot(),r.pivotDistancePercent(),r.volumeDryUp(),r.breakoutVolumeRatio(),r.weightedRelativeStrength(),r.rsPercentile(),r.trendPassed(),r.trendChecks(),null);}
}
