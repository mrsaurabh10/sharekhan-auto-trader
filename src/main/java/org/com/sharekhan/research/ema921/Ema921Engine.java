package org.com.sharekhan.research.ema921;

import java.time.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/** Deterministic research replay. No broker, persistence, scheduler or order dependencies. */
public final class Ema921Engine {
    static final LocalTime OPEN=LocalTime.of(9,15), CLOSE=LocalTime.of(15,30), SQUARE_OFF=LocalTime.of(15,0);
    public enum Mode { ITM5_BUY, ATM_BUY, ATM_SELL }
    public record Bar(LocalDateTime at,double open,double high,double low,double close) {
        public Bar {
            if(at==null||at.getSecond()!=0||at.getNano()!=0||!positive(open)||!positive(high)||!positive(low)||!positive(close)
                    ||low>Math.min(open,close)||high<Math.max(open,close)||low>high)
                throw new IllegalArgumentException("Invalid minute OHLC or timestamp");
        }
    }
    public record Contract(String id,LocalDate listedOn,LocalDate expiry,int strike,String right,int lotSize) {
        public Contract {
            if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,100}")||listedOn==null||expiry==null||listedOn.isAfter(expiry)
                    ||strike<=0||strike%50!=0||!Set.of("CE","PE").contains(right)||lotSize<1||lotSize>1000)
                throw new IllegalArgumentException("Invalid NIFTY NSE_FNO contract metadata");
        }
    }
    public record OptionBar(Contract contract,Bar bar) {
        public OptionBar { if(contract==null||bar==null||bar.at().toLocalDate().isBefore(contract.listedOn())||bar.at().toLocalDate().isAfter(contract.expiry())) throw new IllegalArgumentException("Quote outside contract lifetime"); }
    }
    /** All rates are fractions, not percentages. Caller supplies a dated fee schedule. */
    public record Fees(LocalDate from,LocalDate to,String source,@JsonProperty(required=true) double brokeragePerOrder,@JsonProperty(required=true) double sttSell,@JsonProperty(required=true) double exchange,
                       @JsonProperty(required=true) double sebi,@JsonProperty(required=true) double stampBuy,@JsonProperty(required=true) double gst,@JsonProperty(required=true) double ipft) {
        public Fees {
            if(from==null||to==null||from.isAfter(to)||source==null||source.isBlank()||source.length()>500) throw new IllegalArgumentException("Dated fee source required");
            for(double n:new double[]{brokeragePerOrder,sttSell,exchange,sebi,stampBuy,gst,ipft}) if(!Double.isFinite(n)||n<0) throw new IllegalArgumentException("Invalid fee rate");
            if(brokeragePerOrder>1000||sttSell>0.1||exchange>0.1||sebi>0.1||stampBuy>0.1||gst>1||ipft>0.1) throw new IllegalArgumentException("Fee rate out of bounds; supply fractions");
        }
    }
    public record Config(int timeframe,LocalTime start,LocalTime cutoff,LocalDate from,LocalDate to,
                         double slippageRate,double fixedPoints,Double capital,List<Fees> fees) {
        public Config {
            if(!Set.of(5,15,30).contains(timeframe)||start==null||!Set.of(LocalTime.of(9,30),LocalTime.of(10,0),LocalTime.of(10,30)).contains(start)
                    ||cutoff==null||cutoff.isBefore(LocalTime.of(11,30))||cutoff.isAfter(LocalTime.of(14,30))||cutoff.getMinute()%30!=0||cutoff.getSecond()!=0||cutoff.getNano()!=0
                    ||(from!=null&&to!=null&&from.isAfter(to))||!Double.isFinite(slippageRate)||slippageRate<0||slippageRate>=1
                    ||!Double.isFinite(fixedPoints)||fixedPoints<0||fixedPoints>1000||(capital!=null&&(!positive(capital)||capital>1e12)))
                throw new IllegalArgumentException("Invalid timeframe, window, range, capital or slippage");
            fees=fees==null?List.of():List.copyOf(fees);
            if(fees.size()>100) throw new IllegalArgumentException("Too many fee periods");
            for(int i=0;i<fees.size();i++) for(int j=0;j<i;j++) if(!fees.get(i).from().isAfter(fees.get(j).to())&&!fees.get(j).from().isAfter(fees.get(i).to())) throw new IllegalArgumentException("Overlapping fee periods");
        }
    }
    public record Candle(LocalDateTime at,double open,double high,double low,double close,Double ema9,Double ema21) { }
    public record SignalTrade(int id,String direction,int cross,LocalDateTime signalAt,LocalDateTime entryAt,LocalDateTime exitAt,
                              double signalSpot,double entrySpot,double exitSpot,String reason,boolean whipsaw) { }
    public record Costs(double slippage,double brokerage,double stt,double exchange,double sebi,double stamp,double gst,double ipft,double total) { }
    public record Trade(SignalTrade signal,Mode mode,String status,String issue,Contract contract,Double entryPremium,Double exitPremium,
                        Double entryFill,Double exitFill,Double gross,Costs costs,Double net) { }
    public record Metrics(Mode mode,String status,int trades,int priced,int netPriced,Integer wins,Double winRate,Double gross,Double net,
                          Double profitFactor,Double payoff,Double expectancy,Double maxDrawdown,Double maxDrawdownPercent) { }
    public record Day(LocalDate date,String status,int signals,int trades,String detail) { }
    public record Report(Config config,String evidenceStatus,List<String> limitations,List<Candle> candles,List<Day> days,List<Trade> trades,List<Metrics> metrics) { }
    private record Pending(int id,boolean bull,int cross,LocalDateTime at,double spot,double entry) { }
    private static boolean positive(double d){return Double.isFinite(d)&&d>0&&d<1e9;}

    public Report run(List<Bar> input,List<OptionBar> options,Config config) {
        if(input==null||input.isEmpty()||input.size()>500_000||options==null||options.size()>1_000_000) throw new IllegalArgumentException("Empty or oversized data");
        var sorted=new TreeMap<LocalDateTime,Bar>();
        for(var b:input) {
            if(b.at().getDayOfWeek().getValue()>5||b.at().toLocalTime().isBefore(OPEN)||!b.at().toLocalTime().isBefore(CLOSE)) throw new IllegalArgumentException("Spot rows must be weekday NSE session minute opens in IST");
            if(sorted.putIfAbsent(b.at(),b)!=null) throw new IllegalArgumentException("Duplicate spot minute");
        }
        var byDay=new TreeMap<LocalDate,List<Bar>>(); sorted.values().forEach(b->byDay.computeIfAbsent(b.at().toLocalDate(),k->new ArrayList<>()).add(b));
        var candles=new ArrayList<Candle>();var days=new ArrayList<Day>();var plans=new ArrayList<SignalTrade>();
        Ema fast=new Ema(9),slow=new Ema(21);int relation=0;LocalDate previous=null;int nextId=1;
        for(var day:byDay.entrySet()) {
            LocalDate date=day.getKey();if(config.to()!=null&&date.isAfter(config.to())) break;
            boolean included=(config.from()==null||!date.isBefore(config.from()));var bars=day.getValue();
            boolean complete=bars.size()==375;
            for(int i=0;complete&&i<375;i++) complete=bars.get(i).at().equals(date.atTime(OPEN).plusMinutes(i));
            if(!complete) { if(included)days.add(new Day(date,"REJECTED",0,0,"Requires all 375 contiguous native 1m bars; EMA reset"));fast=new Ema(9);slow=new Ema(21);relation=0;previous=date;continue; }
            if(previous!=null&&date.isAfter(previous.plusDays(7))) {fast=new Ema(9);slow=new Ema(21);relation=0;}
            previous=date;Pending active=null;int cross=0,dayTrades=0;
            for(int i=0;i+config.timeframe()<=bars.size();i+=config.timeframe()) {
                var first=bars.get(i);var last=bars.get(i+config.timeframe()-1);double high=0,low=Double.MAX_VALUE;
                for(int j=i;j<i+config.timeframe();j++){high=Math.max(high,bars.get(j).high());low=Math.min(low,bars.get(j).low());}
                Double f=fast.add(last.close()),s=slow.add(last.close());
                if(included)candles.add(new Candle(first.at(),first.open(),high,low,last.close(),f,s));
                int newRelation=f==null||s==null?0:Double.compare(f,s);
                boolean crossed=newRelation!=0&&relation!=0&&newRelation!=relation;
                // Exact equality retains the last nonzero relation; initial ordering is not a cross.
                if(newRelation!=0) relation=newRelation;
                LocalDateTime closedAt=first.at().plusMinutes(config.timeframe());
                if(crossed)cross++;
                if(!included)continue;
                // 15:00 can fall inside a 30m bar; its open is used before any later signal.
                if(active!=null&&!closedAt.toLocalTime().isBefore(SQUARE_OFF)) {
                    plans.add(finish(active,date.atTime(SQUARE_OFF),bars.get(345).open(),"SQUARE_OFF",config.timeframe()));active=null;dayTrades++;
                }
                if(!closedAt.toLocalTime().isBefore(SQUARE_OFF))continue;
                if(crossed) {
                    double nextOpen=bars.get(i+config.timeframe()).open();
                    if(active!=null) {plans.add(finish(active,closedAt,nextOpen,"OPPOSITE_CROSS",config.timeframe()));active=null;dayTrades++;}
                    if(!closedAt.toLocalTime().isBefore(config.start())&&!closedAt.toLocalTime().isAfter(config.cutoff()))
                        active=new Pending(nextId++,newRelation>0,cross,closedAt,last.close(),nextOpen);
                }
            }
            if(included)days.add(new Day(date,dayTrades==0?"NO_TRADES":"OK",cross,dayTrades,"Closed-bar signals; complete session"));
        }
        if(days.isEmpty())throw new IllegalArgumentException("No observed sessions in requested range");
        var book=new HashMap<String,Map<LocalDateTime,Bar>>();var contracts=new HashMap<String,Contract>();
        var identities=new HashMap<String,String>();
        for(var q:options) {
            var c=q.contract();var old=contracts.putIfAbsent(c.id(),c);
            if(old!=null&&!old.equals(c))throw new IllegalArgumentException("Conflicting contract metadata");
            String key=c.expiry()+"|"+c.strike()+"|"+c.right();var prior=identities.putIfAbsent(key,c.id());
            if(prior!=null&&!prior.equals(c.id()))throw new IllegalArgumentException("Ambiguous duplicate contract identity");
            var b=q.bar();if(b.at().toLocalTime().isBefore(OPEN)||!b.at().toLocalTime().isBefore(CLOSE)||b.at().getDayOfWeek().getValue()>5) throw new IllegalArgumentException("Option quote outside NSE session");
            if(book.computeIfAbsent(c.id(),k->new HashMap<>()).putIfAbsent(b.at(),b)!=null)throw new IllegalArgumentException("Duplicate contract quote minute");
        }
        var trades=new ArrayList<Trade>();
        for(var p:plans)for(var mode:Mode.values())trades.add(price(p,mode,contracts.values(),book,config));
        return new Report(config,"USER_SUPPLIED_UNVERIFIED",List.of(
                "Research only: no live/paper orders, subscriptions, margin model or profitability validation.",
                "Native NIFTY spot 1m and exact NSE_FNO option contract data required; no proxy premium or rolling-strike exit substitution.",
                "Contract catalogue, listing/expiry dates, lot sizes and fee sources are caller-supplied and not independently verified.",
                "Nearest listed unexpired catalogue expiry; ATM nearest 50 (ties upward), ITM5=250 points. ATM sell: bullish short PE, bearish short CE.",
                "Exact minute-open fills assume liquidity; 1m OHLC cannot establish bid/ask fills or short-option margin feasibility.",
                "Missing observed sessions/holidays are not verified. Gaps over seven days and rejected sessions reset EMA.",
                "Drawdown is realized sequential P&L, not mark-to-market. Percent needs supplied capital; fees approximate per-trade rounding."),
                List.copyOf(candles),List.copyOf(days),List.copyOf(trades),Arrays.stream(Mode.values()).map(m->metrics(m,trades,config.capital())).toList());
    }
    private SignalTrade finish(Pending p,LocalDateTime exit,double spot,String reason,int timeframe) {
        return new SignalTrade(p.id(),p.bull()?"BULLISH":"BEARISH",p.cross(),p.at(),p.at(),exit,p.spot(),p.entry(),spot,reason,
                reason.equals("OPPOSITE_CROSS")&&Duration.between(p.at(),exit).toMinutes()<=timeframe);
    }
    private Trade price(SignalTrade p,Mode mode,Collection<Contract> contracts,Map<String,Map<LocalDateTime,Bar>> book,Config config) {
        boolean bull=p.direction().equals("BULLISH"),buy=mode!=Mode.ATM_SELL;
        String right=(bull==buy)?"CE":"PE";int atm=(int)Math.floor(p.signalSpot()/50+0.5)*50;
        int strike=atm+(mode==Mode.ITM5_BUY?(bull?-250:250):0);LocalDate date=p.entryAt().toLocalDate();
        var expiry=contracts.stream().filter(c->!c.listedOn().isAfter(date)&&!c.expiry().isBefore(date)).map(Contract::expiry).min(LocalDate::compareTo).orElse(null);
        var contract=contracts.stream().filter(c->c.expiry().equals(expiry)&&c.strike()==strike&&c.right().equals(right)&&!c.listedOn().isAfter(date)).findFirst().orElse(null);
        if(contract==null)return unavailable(p,mode,null,"Missing exact nearest-expiry "+right+" strike "+strike);
        var entry=book.get(contract.id()).get(p.entryAt());var exit=book.get(contract.id()).get(p.exitAt());
        if(entry==null||exit==null)return unavailable(p,mode,contract,"Missing exact entry/exit minute on SAME contract");
        double ep=entry.open(),xp=exit.open(),ef=fill(ep,buy,config),xf=fill(xp,!buy,config),q=contract.lotSize();
        if(ef<=0||xf<=0)return unavailable(p,mode,contract,"Slippage leaves nonpositive premium");
        double gross=money((buy?1:-1)*(xp-ep)*q),after=money((buy?1:-1)*(xf-ef)*q),slippage=money(gross-after);
        Fees f=config.fees().stream().filter(v->!date.isBefore(v.from())&&!date.isAfter(v.to())).findFirst().orElse(null);
        if(f==null)return new Trade(p,mode,"GROSS_ONLY","No dated fee schedule for trade date",contract,ep,xp,ef,xf,gross,new Costs(slippage,0,0,0,0,0,0,0,slippage),null);
        double bought=(buy?ef:xf)*q,sold=(buy?xf:ef)*q,turnover=bought+sold;
        double brokerage=money(2*f.brokeragePerOrder()),stt=money(sold*f.sttSell()),exchange=money(turnover*f.exchange()),sebi=money(turnover*f.sebi()),stamp=money(bought*f.stampBuy()),ipft=money(turnover*f.ipft());
        double gst=money((brokerage+exchange+sebi+ipft)*f.gst()),total=money(slippage+brokerage+stt+exchange+sebi+stamp+ipft+gst);
        return new Trade(p,mode,"PRICED","",contract,ep,xp,ef,xf,gross,new Costs(slippage,brokerage,stt,exchange,sebi,stamp,gst,ipft,total),money(gross-total));
    }
    private Trade unavailable(SignalTrade p,Mode m,Contract c,String issue) {return new Trade(p,m,"UNPRICED",issue,c,null,null,null,null,null,null,null);}
    static double fill(double premium,boolean buy,Config c) {double adjusted=premium+(buy?1:-1)*(premium*c.slippageRate()+c.fixedPoints());return (buy?Math.ceil(adjusted/0.05-1e-9):Math.floor(adjusted/0.05+1e-9))*0.05;}
    static double money(double value){return Math.round(value*100)/100d;}
    static Metrics metrics(Mode mode,List<Trade> all,Double capital) {
        var ts=all.stream().filter(t->t.mode()==mode).toList();int priced=0,np=0,wins=0,losses=0;double gross=0,net=0,gain=0,loss=0,peak=0,dd=0,ddPct=0;
        for(var t:ts) {
            if(t.gross()!=null){priced++;gross+=t.gross();}
            if(t.net()==null)continue;np++;net+=t.net();
            if(t.net()>0){wins++;gain+=t.net();}else if(t.net()<0){losses++;loss-=t.net();}
            peak=Math.max(peak,net);dd=Math.max(dd,peak-net);
            if(capital!=null&&capital+peak>0)ddPct=Math.max(ddPct,100*(peak-net)/(capital+peak));
        }
        boolean full=np==ts.size(),gp=priced==ts.size();
        return new Metrics(mode,ts.isEmpty()?"NO_TRADES":full?"COMPLETE":gp?"GROSS_ONLY":"INCOMPLETE",ts.size(),priced,np,full?wins:null,
                full&&np>0?100d*wins/np:null,gp?money(gross):null,full?money(net):null,full&&loss>0?gain/loss:null,
                full&&wins>0&&losses>0?(gain/wins)/(loss/losses):null,full&&np>0?net/np:null,full?money(dd):null,full&&capital!=null?ddPct:null);
    }
    static final class Ema {
        final int period;int n;double sum,value;Ema(int period){this.period=period;}
        Double add(double close){n++;if(n<=period){sum+=close;if(n<period)return null;value=sum/period;}else value+=(close-value)*2/(period+1);return value;}
    }
}
