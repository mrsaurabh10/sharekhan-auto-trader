package org.com.sharekhan.analysis;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Selected chart methods, computed from saved NSE data. No subjective scenario probabilities. */
@Service
public class WeeklyTechnicalService {
 public static final String VERSION="NSE_WEEKLY_WITH_DAILY_SMA200_V1";
 private final SavedDailyAnalysis daily;private final SwingSessions calendar;private final Clock clock;
 private final Map<String,Report> cache=new HashMap<>();
 public record Week(LocalDate weekStart,LocalDate lastSession,double open,double high,double low,double close,long volume,int sessions,boolean calendarVerified,Double sma20Week,Double sma50Week,Double sma200Day) { }
 public record Pivot(String type,LocalDate weekStart,double price) { }
 public record Zone(String role,double low,double high,int touches,List<LocalDate> weeks) { }
 public record Scenario(String name,String condition,String invalidation) { }
 public record Report(String version,String symbol,String provider,String timezone,LocalDate dataDate,LocalDate weeklyThrough,Instant generatedAt,
  String status,String trend,String reason,Double sma20Week,Double sma50Week,Double sma200Day,Double slope20WeekPercent,Double slope50WeekPercent,
  Double volumePerSessionRatio,Zone support,Zone resistance,List<Week> weeks,List<Pivot> pivots,List<Zone> zones,List<String> evidence,List<Scenario> scenarios,List<String> rules) { }
 @org.springframework.beans.factory.annotation.Autowired
 public WeeklyTechnicalService(SavedDailyAnalysis daily,SwingSessions calendar){this(daily,calendar,Clock.system(StockAnalysisService.IST));}
 WeeklyTechnicalService(SavedDailyAnalysis daily,SwingSessions calendar,Clock clock){this.daily=daily;this.calendar=calendar;this.clock=clock;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=20)
 public synchronized Report report(String requested) {
  String symbol=requested==null?"":requested.trim().toUpperCase(Locale.ROOT);
  if(symbol.length()>40||!symbol.matches("[A-Z0-9&.\\-]+"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid NSE cash symbol");
  var members=daily.members();if(members.stream().noneMatch(m->m.symbol().equals(symbol)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No saved Nifty 200 dataset for symbol");
  var day=calendar.expected(LocalDateTime.now(clock));var previous=cache.get(symbol);
  if(previous!=null&&Objects.equals(previous.dataDate(),day)&&Duration.between(previous.generatedAt(),clock.instant()).getSeconds()<60)return previous;
  if(day==null)return unavailable(symbol,"Verified session calendar unavailable.",null);
  var input=daily.read(symbol,day);
  var result=evaluate(input,day);cache.put(symbol,result);return result;
 }
 Report evaluate(StockDailyHistoryStore.Saved input,LocalDate day) {
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
        if(!day.equals(last)||sorted.size()<20)return unavailable(symbol,"Requires current completed session and at least 20 daily bars.",last);
        LocalDate check=day;int sessions=0;
        while(sessions<20&&calendar.supported(check)) {
            if(calendar.regular(check)||check.equals(LocalDate.of(2026,2,1))){if(!sorted.containsKey(check))return unavailable(symbol,"Missing recent completed session.",last);sessions++;}
            check=check.minusDays(1);
        }
        if(sessions<20)return unavailable(symbol,"Recent session calendar unavailable.",last);
        var bars=new ArrayList<>(sorted.values());Collections.reverse(bars);
        for(int i=0;i<bars.size()-1;i++)if(Math.abs(bars.get(i).close()/bars.get(i+1).close()-1)>.30)return unavailable(symbol,"Price discontinuity requires corporate-action review.",last);
        for(int i=0;i<20;i++)if(bars.get(i).volume()<=0)return unavailable(symbol,"Recent daily volumes must be positive.",last);
        var groups=new TreeMap<LocalDate,List<HistoricalCandle>>();
        for(var c:sorted.values())groups.computeIfAbsent(monday(c.date()),k->new ArrayList<>()).add(c);
        var weeks=new ArrayList<Week>();int omitted=0;
        LocalDate firstWeek=groups.firstKey();
        for(var e:groups.entrySet()) {
            LocalDate start=e.getKey(),friday=start.plusDays(4);var candles=e.getValue();
            // First archive week may begin midweek or mid-IPO; always omit it.
            if(start.equals(firstWeek)){omitted++;continue;}
            boolean verified=true,complete=true;LocalDate finalSession=null;
            for(int d=0;d<7;d++){
                LocalDate date=start.plusDays(d);
                if(!calendar.supported(date)){verified=false;continue;}
                if(calendar.special(date)){complete=false;continue;}
                boolean expected=calendar.regular(date)||date.equals(LocalDate.of(2026,2,1));
                if(expected){finalSession=date;if(date.isAfter(day)||!sorted.containsKey(date))complete=false;}
            }
            // Older calendar is unverified: only closed calendar weeks, never infer missing sessions as holidays.
            if(!verified&&friday.isAfter(day))complete=false;
            if(!complete||verified&&finalSession==null){omitted++;continue;}
            var first=candles.getFirst();var lastC=candles.getLast();
            double high=candles.stream().mapToDouble(HistoricalCandle::high).max().orElseThrow(),low=candles.stream().mapToDouble(HistoricalCandle::low).min().orElseThrow();
            long volume=0;for(var c:candles)volume=Math.addExact(volume,c.volume());
            List<HistoricalCandle> toDate=sorted.headMap(lastC.date(),true).values().stream().toList();
            Double d200=toDate.size()>=200?toDate.subList(toDate.size()-200,toDate.size()).stream().mapToDouble(HistoricalCandle::close).average().orElseThrow():null;
            weeks.add(new Week(start,lastC.date(),first.open(),high,low,lastC.close(),volume,candles.size(),verified,null,null,d200));
        }
        if(weeks.isEmpty())return unavailable(symbol,"No usable closed weekly bars.",day);
        // An omitted week breaks rolling weekly averages; never compress across a known gap.
        for(int i=0;i<weeks.size();i++){var w=weeks.get(i);weeks.set(i,new Week(w.weekStart(),w.lastSession(),w.open(),w.high(),w.low(),w.close(),w.volume(),w.sessions(),w.calendarVerified(),average(weeks,i,20),average(weeks,i,50),w.sma200Day()));}
        var latest=weeks.getLast();int n=weeks.size();
        Double slope20=n>=5?change(latest.sma20Week(),weeks.get(n-5).sma20Week()):null,slope50=n>=5?change(latest.sma50Week(),weeks.get(n-5).sma50Week()):null;
        var pivots=new ArrayList<Pivot>();int from=Math.max(2,n-52);
        for(int i=from;i<n-2;i++){
            boolean high=true,low=true;for(int d=-2;d<=2;d++)if(d!=0){
                if(!weeks.get(i+d).weekStart().equals(weeks.get(i).weekStart().plusWeeks(d))){high=false;low=false;break;}
                high&=weeks.get(i).high()>weeks.get(i+d).high();low&=weeks.get(i).low()<weeks.get(i+d).low();}
            if(high)pivots.add(new Pivot("HIGH",weeks.get(i).weekStart(),weeks.get(i).high()));if(low)pivots.add(new Pivot("LOW",weeks.get(i).weekStart(),weeks.get(i).low()));
        }
        var highs=pivots.stream().filter(v->v.type().equals("HIGH")).toList();var lows=pivots.stream().filter(v->v.type().equals("LOW")).toList();
        boolean hh=highs.size()>=2&&highs.getLast().price()>highs.get(highs.size()-2).price(),hl=lows.size()>=2&&lows.getLast().price()>lows.get(lows.size()-2).price();
        boolean lh=highs.size()>=2&&highs.getLast().price()<highs.get(highs.size()-2).price(),ll=lows.size()>=2&&lows.getLast().price()<lows.get(lows.size()-2).price();
        boolean above20=latest.sma20Week()!=null&&latest.close()>latest.sma20Week(),above50=latest.sma50Week()!=null&&latest.close()>latest.sma50Week(),above200=latest.sma200Day()!=null&&latest.close()>latest.sma200Day();
        boolean complete=latest.sma20Week()!=null&&latest.sma50Week()!=null&&latest.sma200Day()!=null&&slope20!=null&&slope50!=null;
        String trend=complete&&hh&&hl&&above20&&above50&&above200&&latest.sma20Week()>latest.sma50Week()&&slope20>0&&slope50>0?"UPTREND":
            complete&&lh&&ll&&!above20&&!above50&&!above200&&latest.sma20Week()<latest.sma50Week()&&slope20<0&&slope50<0?"DOWNTREND":"MIXED_OR_RANGE";
        var zones=zones(pivots,latest.close());
        Zone support=zones.stream().filter(z->z.role().equals("SUPPORT")).max(Comparator.comparingDouble(Zone::high)).orElse(null),resistance=zones.stream().filter(z->z.role().equals("RESISTANCE")).min(Comparator.comparingDouble(Zone::low)).orElse(null);
        Double volumeRatio=null;
        if(n>=11&&consecutive(weeks,n-1,11)){var previous=weeks.subList(n-11,n-1);double denominator=previous.stream().mapToDouble(Week::volume).sum()/previous.stream().mapToInt(Week::sessions).sum();if(denominator>0)volumeRatio=(latest.volume()/(double)latest.sessions())/denominator;}
        var evidence=new ArrayList<String>();evidence.add("Latest closed week "+latest.weekStart()+" through "+latest.lastSession()+"; "+latest.sessions()+" observed sessions. "+omitted+" partial/incomplete weeks omitted.");
        evidence.add("Weekly close "+latest.close()+" versus 20-week SMA: "+position(latest.close(),latest.sma20Week())+"; 50-week SMA: "+position(latest.close(),latest.sma50Week())+"; 200-day SMA: "+position(latest.close(),latest.sma200Day())+".");
        evidence.add("Last two confirmed highs: "+(highs.size()<2?"unavailable":hh?"higher":"lower or equal")+"; last two confirmed lows: "+(lows.size()<2?"unavailable":hl?"higher":"lower or equal")+". Confirmation requires two closed weeks each side.");
        evidence.add("20/50-week slope uses four-week change; daily SMA200 is independently calculated from 200 daily closes through the same last session, not from weekly closes.");
        evidence.add("Weekly volume: "+latest.volume()+" shares across "+latest.sessions()+" sessions. Per-session volume versus previous ten weeks: "+(volumeRatio==null?"unavailable":String.format(Locale.ROOT,"%.2f×",volumeRatio))+"; does not measure buying/selling flow.");
        if(weeks.stream().anyMatch(w->!w.calendarVerified()))evidence.add("2025 session calendar unverified: older weekly bars aggregate observed sessions only; historical gaps/holidays cannot be certified. 50-week averages inherit this limitation.");
        var scenarios=new ArrayList<Scenario>();
        if(support!=null&&resistance!=null)scenarios.add(new Scenario("RANGE","Closed-week price remains between support upper "+support.high()+" and resistance lower "+resistance.low(),"A closed-week move outside those boundaries."));
        if(resistance!=null)scenarios.add(new Scenario("UPSIDE_CONFIRMATION","Future weekly close above resistance upper "+resistance.high()+" with volume confirmation; chart review required.","Return below resistance lower "+resistance.low()+"; this is context, not an entry order."));
        if(support!=null)scenarios.add(new Scenario("DOWNSIDE_RISK","Future weekly close below support lower "+support.low(),"Recovery above support upper "+support.high()+"; no short-entry instruction."));
        String state=complete?"AVAILABLE":"LIMITED_HISTORY";
        return new Report(VERSION,symbol,SavedDailyAnalysis.PROVIDER,"Asia/Kolkata",day,latest.lastSession(),clock.instant(),state,trend,complete?"Weekly chart context available; separate setup/event/portfolio review required.":"One or more moving averages/slopes unavailable; no complete trend classification.",latest.sma20Week(),latest.sma50Week(),latest.sma200Day(),slope20,slope50,volumeRatio,support,resistance,List.copyOf(weeks),List.copyOf(pivots),zones,List.copyOf(evidence),List.copyOf(scenarios),rules());
 }
 static LocalDate monday(LocalDate d){return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));}
 static boolean consecutive(List<Week> w,int end,int count){if(end+1<count)return false;for(int i=end-count+1;i<=end;i++)if(!w.get(i).weekStart().equals(w.get(end).weekStart().minusWeeks(end-i)))return false;return true;}
 static Double average(List<Week> w,int end,int count){return consecutive(w,end,count)?w.subList(end-count+1,end+1).stream().mapToDouble(Week::close).average().orElseThrow():null;}
 static Double change(Double a,Double b){return a==null||b==null||b<=0?null:100*(a/b-1);}
 static String position(double close,Double average){return average==null?"unavailable":close>average?"above":close<average?"below":"at";}
 static List<Zone> zones(List<Pivot> pivots,double close){
  var sorted=pivots.stream().sorted(Comparator.comparingDouble(Pivot::price)).toList();var result=new ArrayList<Zone>();
  for(int i=0;i<sorted.size();){double anchor=sorted.get(i).price();int j=i;var dates=new TreeSet<LocalDate>();
   while(j<sorted.size()&&sorted.get(j).price()<=anchor*1.01){dates.add(sorted.get(j).weekStart());j++;}
   double low=anchor*.995,high=sorted.get(j-1).price()*1.005;result.add(new Zone(close>high?"SUPPORT":close<low?"RESISTANCE":"TESTING",low,high,dates.size(),List.copyOf(dates)));i=j;
  }return List.copyOf(result);
 }
 private Report unavailable(String symbol,String reason,LocalDate last){return new Report(VERSION,symbol,SavedDailyAnalysis.PROVIDER,"Asia/Kolkata",last,null,clock.instant(),"UNAVAILABLE","UNAVAILABLE",reason,null,null,null,null,null,null,null,null,List.of(),List.of(),List.of(),List.of(),List.of(),rules());}
 private static List<String> rules(){return List.of(
  "Selected Technical Analyst methods adapted to saved NSE daily candles and generated weekly charts. User-requested 200-DAY SMA replaces the upstream 200-week reference; the horizons are not equivalent. No subjective AI chart verdict or probability estimates.",
  "20/50-week averages use closed-week closes. 200-day SMA uses exactly 200 daily closes through each week's final session; all latest indicators share weeklyThrough cutoff. Unfinished current weeks and first archive week excluded; gaps in verified 2026 sessions exclude affected weeks and break weekly MA continuity.",
  "Verified research calendar covers 2026 only; older bars aggregate observed sessions without certified historical completeness. February 1 Budget session included in its Monday–Sunday week. Unsupported specials require review.",
  "Trend requires two confirmed higher/lower pivot highs AND lows, close position versus all three averages, 20/50-week ordering and four-week slopes. Mixed/range means conditions do not align; it is not a buy/sell signal. Pivots require two strict neighbouring weeks on each side, with no gaps.",
  "Horizontal zones cluster confirmed pivot prices within 1% of lowest cluster price, observed cluster minimum/maximum expanded by 0.5%. Touches count distinct pivot weeks; one touch is weak evidence, two or more still require manual chart review. No fallback invented levels. Zones can change role relative to latest close.",
  "Volume ratio uses average volume per observed session versus prior ten consecutive weeks to reduce holiday-week length effects. Conditional range/upside/downside scenarios are unweighted context, not calibrated probabilities, executable targets or automatic trading instructions.",
  "Dhan saved NSE_EQ/current Nifty 200 universe, INR/share and IST. Adjustment UNKNOWN; >30% discontinuities and malformed/stale/recently missing inputs fail closed. Company/news/earnings, portfolio, costs and real-time execution checks remain separate. No orders, new feed, settings or scheduled job.");}
}
