package org.com.sharekhan.analysis;

import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.SwingModels.Plan;

/** Native, DB-only NSE adaptation; no broker/order/session or installed Python dependency. */
@Service
public class MomentumResearchService {
    public static final String VERSION="NSE_MOMENTUM_PERCENT_RANGE_V1";
    private final SavedDailyAnalysis daily;
    private final SwingSessions calendar;
    private final Clock clock;
    private volatile Report cached;
    public record Row(String symbol,String status,String reason,LocalDate lastDate,List<String> triggers,
                      Double dayGainPercent,Double volumeRatio,Double closeLocation,int score,
                      int baseDays,boolean above50,Plan plan) { }
    public record Report(String version,LocalDate dataDate,LocalDate validSession,Instant generatedAt,
                         String status,int universeCount,int coveredCount,double breadthAbove50,
                         List<Row> stocks,List<String> rules) { }
    @org.springframework.beans.factory.annotation.Autowired
    public MomentumResearchService(SavedDailyAnalysis daily,SwingSessions calendar){this(daily,calendar,Clock.system(StockAnalysisService.IST));}
    MomentumResearchService(SavedDailyAnalysis daily,SwingSessions calendar,Clock clock){this.daily=daily;this.calendar=calendar;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=20)
    public synchronized Report report() {
        Instant now=clock.instant();LocalDate day=calendar.expected(LocalDateTime.now(clock));
        if(cached!=null&&Objects.equals(cached.dataDate(),day)&&Duration.between(cached.generatedAt(),now).getSeconds()<60)return cached;
        var members=daily.members();var rows=new ArrayList<Row>();LocalDate valid=day==null?null:calendar.next(day);
        var identities=new HashMap<String,List<String>>();
        for(var member:members) {
            if(day==null){rows.add(unavailable(member.symbol(),"Verified session calendar unavailable.",null));continue;}
            var input=daily.read(member.symbol(),day);
            if(input.securityId()!=null)identities.computeIfAbsent(input.securityId(),k->new ArrayList<>()).add(member.symbol());
            rows.add(evaluate(input,day,valid));
        }
        var collisions=new HashSet<String>();identities.values().stream().filter(v->v.size()>1).forEach(collisions::addAll);
        rows.replaceAll(r->collisions.contains(r.symbol())?unavailable(r.symbol(),"Conflicting instrument identity in saved archive.",r.lastDate()):r);
        long covered=rows.stream().filter(r->!r.status().equals("UNAVAILABLE")).count();
        double breadth=covered==0?0:rows.stream().filter(r->!r.status().equals("UNAVAILABLE")&&r.above50()).count()/(double)covered;
        String gate=day==null||valid==null||members.size()!=200||covered<Math.ceil(members.size()*.8)?"DATA_INCOMPLETE":breadth<.5?"MARKET_CAUTION":"READY";
        var published=new ArrayList<Row>();
        for(var r:rows) {
            if(r.plan()!=null&&!gate.equals("READY"))published.add(new Row(r.symbol(),"WATCH_ONLY",r.reason()+" Entry levels withheld by "+gate+".",r.lastDate(),r.triggers(),r.dayGainPercent(),r.volumeRatio(),r.closeLocation(),r.score(),r.baseDays(),r.above50(),null));
            else published.add(r);
        }
        published.sort(Comparator.comparingInt(Row::score).reversed().thenComparing(Row::symbol));
        cached=new Report(VERSION,day,valid,now,gate,members.size(),(int)covered,breadth,List.copyOf(published),List.of(
            "Saved Dhan NSE cash candles; INR per share, IST dates. Current Nifty 200 snapshot; adjustment UNKNOWN. Not point-in-time membership.",
            "Stockbee adaptation: 4% close-to-close rise and/or range greater than prior 3 days, rising volume and at least 100,000 shares. Dollar and 9-million-share triggers disabled.",
            "20-day average turnover at least ₹20 crore; 220 bars and all recent 20 sessions. At least 80% data coverage and 50% above SMA50 for entry plans.",
            "Score uses trigger, volume, prior 3–20-day base, close location, risk and failure filters. It is a setup rubric, not probability or validated performance.",
            "Score ≥80 plus bullish close and trend above SMA50 are required for conditional buy plans. Trigger-day low stop; worst-entry risk ≤8%, maximum entry ≤2% above trigger-day high. T1/T2 are projected 2R/3R sell exits.",
            "Plans expire next regular session at 15:15 IST; skip gaps above maximum entry or stop breaches before entry. Review after 3–5 sessions. News, earnings, portfolio and costs require separate review.",
            "No swing short-entry engine or holdings-linked sell advice. No order, broker login or historical API call is made. Other installed skills are listed with their integration status."));
        return cached;
    }
    Row evaluate(StockDailyHistoryStore.Saved input,LocalDate day,LocalDate valid) {
        String symbol=input.symbol();var sorted=new TreeMap<LocalDate,HistoricalCandle>();
        if(input.securityId()==null||!input.securityId().matches("[0-9]{1,9}")||input.tick()==null||input.tick().doubleValue()<.01||input.tick().doubleValue()>5)
            return unavailable(symbol,"Missing saved cash identity or supported tick.",null);
        for(var c:input.candles()) {
            if(c==null||c.date()==null)return unavailable(symbol,"Invalid candle date.",null);
            if(c.date().isAfter(day))continue;
            if(calendar.supported(c.date())&&!calendar.regular(c.date())&&!c.date().equals(LocalDate.of(2026,2,1)))return unavailable(symbol,"Nonregular historical session requires review.",c.date());
            var previous=sorted.putIfAbsent(c.date(),c);
            if(!StockAnalysisService.valid(c)||c.volume()==null||c.volume()<0||previous!=null&&!previous.equals(c))return unavailable(symbol,"Invalid OHLCV or conflicting dates.",c.date());
        }
        LocalDate last=sorted.isEmpty()?null:sorted.lastKey();
        if(!day.equals(last)||sorted.size()<220)return unavailable(symbol,"Requires current completed session and at least 220 daily bars.",last);
        LocalDate check=day;int sessions=0;
        while(sessions<20&&calendar.supported(check)) {
            if(calendar.regular(check)||check.equals(LocalDate.of(2026,2,1))){if(!sorted.containsKey(check))return unavailable(symbol,"Missing recent completed session.",last);sessions++;}
            check=check.minusDays(1);
        }
        if(sessions<20)return unavailable(symbol,"Recent session calendar unavailable.",last);
        var bars=new ArrayList<>(sorted.values());Collections.reverse(bars);
        for(int i=0;i<bars.size()-1;i++)if(Math.abs(bars.get(i).close()/bars.get(i+1).close()-1)>.30)return unavailable(symbol,"Price discontinuity requires corporate-action review.",last);
        for(int i=0;i<20;i++)if(bars.get(i).volume()<=0)return unavailable(symbol,"Recent daily volumes must be positive.",last);
        var l=bars.getFirst();var p=bars.get(1);double sma50=bars.subList(0,50).stream().mapToDouble(HistoricalCandle::close).average().orElseThrow();boolean above=l.close()>sma50;
        double gain=pct(l.close(),p.close()),ratio1=l.volume()/(double)p.volume(),ratio20=l.volume()/bars.subList(1,21).stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow();
        double location=l.high()==l.low()?50:100*(l.close()-l.low())/(l.high()-l.low());
        var tags=new ArrayList<String>();boolean volume=l.volume()>=100_000&&l.volume()>p.volume();
        if(gain>=4&&volume)tags.add("4_PERCENT_BREAKOUT");
        double maxRange=bars.subList(1,4).stream().mapToDouble(b->b.high()-b.low()).max().orElseThrow();
        if(l.high()-l.low()>maxRange&&pct(p.close(),bars.get(2).close())<=2&&volume)tags.add("RANGE_EXPANSION");
        int baseDays=0;double width=0;boolean dry=false;
        for(int n=3;n<=20;n++) {
            var prior=bars.subList(1,n+1);double w=100*(prior.stream().mapToDouble(HistoricalCandle::high).max().orElseThrow()-prior.stream().mapToDouble(HistoricalCandle::low).min().orElseThrow())/p.close();
            double range=prior.stream().mapToDouble(b->100*(b.high()-b.low())/b.close()).average().orElseThrow();
            if(n==3)width=w;
            if(w<=15&&range<=5){baseDays=n;width=w;dry=prior.stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow()<=.85*bars.subList(n+1,2*n+1).stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow();}
        }
        int trigger=Math.min(20,(tags.contains("4_PERCENT_BREAKOUT")?14+(gain>=7?3:0):0)+(tags.contains("RANGE_EXPANSION")?10:0));
        double vr=Math.max(ratio1,ratio20),risk=100*(l.close()-l.low())/l.close();
        int setup=(baseDays>=10?10:baseDays>=5?8:baseDays>=3?6:0)+(width>0&&width<=8?7:width<=12?5:width<=15?3:0)
                +(100*(p.high()-p.low())/p.close()<=3?5:p.close()<p.open()?4:0)+(dry?3:0);
        int streak=0;for(int i=1;i<=5;i++){if(bars.get(i).close()>bars.get(i+1).close())streak++;else break;}
        boolean breakdown=false;for(int i=1;i<=5;i++)if(pct(bars.get(i).close(),bars.get(i+1).close())<=-4)breakdown=true;
        int failure=Math.max(0,10-(streak>=3?4:0)-(breakdown?4:0)-(width>15?3:0)-(location<50?2:0));
        int score=trigger+(vr>=3?15:vr>=2?12:vr>=1.5?9:vr>=1?6:0)+Math.min(25,setup)
                +(location>=90?10:location>=80?9:location>=70?7:location>=60?5:location>=50?3:0)
                +(risk<=0?0:risk<=2.5?15:risk<=4?12:risk<=6?8:risk<=8?5:risk<=10?2:0)+failure;
        // Score excludes the market component; the independent report gate never boosts ranking.
        double turnover=bars.subList(0,20).stream().mapToDouble(b->b.close()*b.volume()).average().orElseThrow();
        String reason=tags.isEmpty()?"No supported momentum trigger.":turnover<200_000_000?"Average daily turnover below ₹20 crore.":risk<=0||risk>10?"Trigger-day stop distance outside 0–10%.":score<55?"Setup score below watchlist threshold.":"Momentum observed; chart, event and portfolio review required.";
        boolean filtered=tags.isEmpty()||turnover<200_000_000||risk<=0||risk>10||score<55;
        Plan plan=null;String state=filtered?"FILTERED":"WATCH_ONLY";
        if(!filtered&&score>=80&&above&&l.close()>l.open()&&valid!=null) {
            double atr=StockAnalysisService.atr(sorted.values().stream().map(c->new StockAnalysisService.Bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList(),14);
            if(Double.isFinite(atr)&&atr>0){double tick=input.tick().doubleValue(),entry=SwingPlanner.round(l.high()+Math.max(tick,.1*atr),tick,true),max=SwingPlanner.round(entry+.25*atr,tick,true),stop=SwingPlanner.round(l.low()-.1*atr,tick,false),r=max-stop;
                if(stop>0&&r>0&&r/max<=.08&&max<=l.high()*1.02){
                    plan=new Plan(symbol,Integer.parseInt(input.securityId()),"NC","STOCKBEE_NSE_MOMENTUM",SavedDailyAnalysis.PROVIDER,day,valid,entry,max,stop,SwingPlanner.round(max+2*r,tick,true),SwingPlanner.round(max+3*r,tick,true),r,100*r/max,atr,StockAnalysisService.rsi(sorted.values().stream().map(c->new StockAnalysisService.Bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList(),14),turnover,
                            "NSE percentage/range momentum adaptation; score "+score+" is a rubric, not confidence. Dhan ID is not an order identity.",List.of("Valid only "+valid+" 09:15–15:15 IST; skip gaps above maximum entry.","Cancel if stop is breached before entry. Review news/earnings and portfolio first.","Sell exits are projected stop/T1/T2; review holding after 3–5 sessions. No orders placed."));
                    state="CONDITIONAL_BUY";
                }else reason="Momentum observed, but worst-entry risk/chase checks block an entry plan.";
            }
        }
        return new Row(symbol,state,reason,last,List.copyOf(tags),gain,ratio20,location,score,baseDays,above,plan);
    }
    private static double pct(double a,double b){return 100*(a/b-1);}
    private static Row unavailable(String symbol,String reason,LocalDate last){return new Row(symbol,"UNAVAILABLE",reason,last,List.of(),null,null,null,0,0,false,null);}
}
