package org.com.sharekhan.analysis;

import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.SwingModels.Plan;

/** Saved-data, after-close NSE price/volume variant. No fundamental-quality inference. */
@Service
public class HammerResearchService {
    public static final String VERSION="NSE_EXHAUSTION_HAMMER_EOD_V1";
    private final SavedDailyAnalysis daily;
    private final SwingSessions calendar;
    private final Clock clock;
    private volatile Report cached;
    public record Evidence(double lowerWickPercent,double bodyPercent,double upperWickPercent,
        double wickToBody,double closeLocation,double recoveryPercent,double pullbackPercent,
        LocalDate highDate,int sessionsSinceHigh,double priorLow,boolean undercutReclaim,
        double prior20ReturnPercent,double highVs61ClosePercent,double volumeRatio,
        double averageTurnover,int downDays10,boolean hammer,boolean priorMomentum) { }
    public record Row(String symbol,String status,String reason,LocalDate lastDate,int score,
        boolean above50,Evidence evidence,Plan plan) { }
    public record Report(String version,LocalDate dataDate,LocalDate validSession,Instant generatedAt,
        String status,int universeCount,int coveredCount,double breadthAbove50,int entryCandidatesBeforeMarketGate,
        List<Row> stocks,List<String> rules) { }
    @org.springframework.beans.factory.annotation.Autowired
    public HammerResearchService(SavedDailyAnalysis daily,SwingSessions calendar){this(daily,calendar,Clock.system(StockAnalysisService.IST));}
    HammerResearchService(SavedDailyAnalysis daily,SwingSessions calendar,Clock clock){this.daily=daily;this.calendar=calendar;this.clock=clock;}
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
        int candidates=(int)rows.stream().filter(r->r.plan()!=null).count();
        rows.replaceAll(r->r.plan()!=null&&!gate.equals("READY")?new Row(r.symbol(),"WATCH_ONLY",r.reason()+" Entry levels withheld by "+gate+".",r.lastDate(),r.score(),r.above50(),r.evidence(),null):r);
        rows.sort(Comparator.comparingInt(Row::score).reversed().thenComparing(Row::symbol));
        cached=new Report(VERSION,day,valid,now,gate,members.size(),(int)covered,breadth,candidates,List.copyOf(rows),List.of(
            "After-close Stockbee-inspired NSE price/volume adaptation, not a near-close live scan or a replication of the full US quality screen.",
            "Saved Dhan NSE cash daily candles; INR/share and IST dates. Current Nifty 200 membership; corporate-action adjustment UNKNOWN. Minimum 220 bars, current completed session and all recent 20 sessions; discontinuities over 30% require review.",
            "Liquidity: previous 20-session average turnover ≥₹20 crore and hammer-day volume ≥100,000. Company market cap, holder quality, earnings/news and portfolio checks are unavailable here and require separate review; no points awarded for unknown fundamentals.",
            "Prior 40-session high must be 3–30 sessions old; close 6–35% below that high. Prior momentum requires that high ≥20% above the 61-session-old close OR prior close ≥10% above the 21-session-old close.",
            "Hammer: lower wick ≥40%, body ≤35%, upper wick ≤35% of range; close location ≥60%, lower-wick/body ≥1.5, recovery from low ≥2%. Zero-body ratio shown as 999 (capped); flat-range bars fail. Prior 5-session low undercut/reclaim is recorded, not mandatory.",
            "Declared score /85: liquidity 10, prior momentum 15, pullback/exhaustion 20, geometry 25, risk 15. Watch ≥55; plans ≥70 plus close above SMA50 and volume ≥prior-20 average. Score is a rubric, not probability or validated edge.",
            "Next-session hammer-high trigger plus max(tick, 0.1 ATR); maximum entry adds 0.25 ATR; stop below hammer low by max(tick, 0.1 ATR). Rounded worst-entry risk ≤8%, maximum entry ≤2% above hammer high. Projected sell targets 2R/3R from maximum entry.",
            "At least 80% of the full 200-stock universe covered and 50% of covered stocks above SMA50 for entry plans. Candidates before this gate are reported separately. Plans valid only next regular session 09:15–15:15 IST; cancel on gap above maximum entry or stop breach before entry; review after 3–5 sessions.",
            "Manual chart, event, costs and portfolio review required before any trade. No orders, broker logins, new feeds/jobs, swing shorts or holdings-linked sell recommendations."));
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
        var l=bars.getFirst();var prior=bars.get(1);
        double sma50=bars.subList(0,50).stream().mapToDouble(HistoricalCandle::close).average().orElseThrow();boolean above=l.close()>sma50;
        int highIndex=1;for(int i=2;i<=40;i++)if(bars.get(i).high()>bars.get(highIndex).high())highIndex=i;
        var high=bars.get(highIndex);double depth=100*(1-l.close()/high.high());
        double priorLow=bars.subList(1,6).stream().mapToDouble(HistoricalCandle::low).min().orElseThrow();
        boolean reclaim=l.low()<priorLow&&l.close()>priorLow;
        double range=l.high()-l.low(),body=Math.abs(l.close()-l.open()),lower=Math.min(l.open(),l.close())-l.low(),upper=l.high()-Math.max(l.open(),l.close());
        double lowerPct=range>0?100*lower/range:0,bodyPct=range>0?100*body/range:100,upperPct=range>0?100*upper/range:100;
        double location=range>0?100*(l.close()-l.low())/range:0,wickRatio=body>0?Math.min(999,lower/body):999,recovery=100*(l.close()/l.low()-1);
        boolean hammer=range>0&&lowerPct>=40&&bodyPct<=35&&upperPct<=35&&location>=60&&wickRatio>=1.5&&recovery>=2;
        double ret20=100*(prior.close()/bars.get(21).close()-1),highGain=100*(high.high()/bars.get(61).close()-1);
        boolean momentum=highGain>=20||ret20>=10;
        double avgVol=bars.subList(1,21).stream().mapToDouble(HistoricalCandle::volume).average().orElseThrow(),vr=l.volume()/avgVol;
        double turnover=bars.subList(1,21).stream().mapToDouble(b->b.close()*b.volume()).average().orElseThrow();
        int down=0;for(int i=1;i<=10;i++)if(bars.get(i).close()<bars.get(i).open())down++;
        double tick=input.tick().doubleValue();
        var series=sorted.values().stream().map(c->new StockAnalysisService.Bar(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList();
        double atr=StockAnalysisService.atr(series,14),entry=0,max=0,stop=0,r=0,risk=100;
        if(Double.isFinite(atr)&&atr>0){entry=SwingPlanner.round(l.high()+Math.max(tick,.1*atr),tick,true);max=SwingPlanner.round(entry+.25*atr,tick,true);stop=SwingPlanner.round(l.low()-Math.max(tick,.1*atr),tick,false);r=max-stop;if(max>0)risk=100*r/max;}
        int liquidity=turnover>=200_000_000&&l.volume()>=100_000?10:0;
        int strength=(momentum?10:0)+(above?5:0);
        int exhaustion=(depth>=6&&depth<=25?6:depth<=35&&depth>=6?4:0)+(reclaim?6:0)+(down>=5?3:down>=3?2:0)+(vr>=1.5?5:vr>=1?3:0);
        int geometry=(lowerPct>=60?8:lowerPct>=40?6:0)+(bodyPct<=20?5:bodyPct<=35?4:0)+(location>=80?5:location>=60?4:0)+(wickRatio>=3?3:wickRatio>=1.5?2:0)+(upperPct<=15?2:upperPct<=35?1:0)+(recovery>=2?2:0);
        int riskScore=stop>0&&r>0?(risk<=3?15:risk<=5?12:risk<=8?8:0):0;
        int score=liquidity+strength+exhaustion+geometry+riskScore;
        var reasons=new ArrayList<String>();
        if(!hammer)reasons.add("Hammer geometry failed");
        if(highIndex<3||highIndex>30)reasons.add("Recent high outside 3–30 sessions");
        if(depth<6||depth>35)reasons.add("Pullback outside 6–35%");
        if(!momentum)reasons.add("Prior momentum not established");
        if(liquidity==0)reasons.add("Liquidity below ₹20 crore prior average or 100,000 current shares");
        if(stop<=0||r<=0||risk>8)reasons.add("Worst-entry risk outside 0–8%");
        if(score<55)reasons.add("Score below 55 watch threshold");
        String status=reasons.isEmpty()?"WATCH_ONLY":"FILTERED";Plan plan=null;
        if(reasons.isEmpty()&&score>=70&&above&&vr>=1&&max<=l.high()*1.02&&valid!=null){
            plan=new Plan(symbol,Integer.parseInt(input.securityId()),"NC","STOCKBEE_NSE_EXHAUSTION_HAMMER",SavedDailyAnalysis.PROVIDER,day,valid,entry,max,stop,SwingPlanner.round(max+2*r,tick,true),SwingPlanner.round(max+3*r,tick,true),r,risk,atr,StockAnalysisService.rsi(series,14),turnover,
                "After-close NSE hammer variant; score "+score+"/85. Fundamental/institutional quality unverified. Dhan ID is not an order identity.",List.of("Valid only "+valid+" 09:15–15:15 IST; skip gaps above maximum entry.","Cancel if stop breached before entry; manually review chart, news/earnings, costs and portfolio.","Projected sell exits only; review after 3–5 sessions. No order placed."));status="CONDITIONAL_BUY";
        }
        String reason=reasons.isEmpty()?plan!=null?"Price/volume setup qualified; manual chart, event and portfolio review required.":"Hammer watch; confirmation requires score ≥70, close above SMA50, volume ≥prior average, ≤2% chase and a verified next session.":String.join("; ",reasons)+".";
        return new Row(symbol,status,reason,last,score,above,new Evidence(lowerPct,bodyPct,upperPct,wickRatio,location,recovery,depth,high.date(),highIndex,priorLow,reclaim,ret20,highGain,vr,turnover,down,hammer,momentum),plan);
    }
    private static Row unavailable(String symbol,String reason,LocalDate last){return new Row(symbol,"UNAVAILABLE",reason,last,0,false,null,null);}
}
