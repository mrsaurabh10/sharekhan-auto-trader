package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;

/** Completed candles only. Missing volume, incomplete fifteen-minute groups and gaps fail closed. */
public final class NaturalGasSignals {
    public record Signal(String side, double trigger, double stop, double atr, LocalDateTime candleEnd) { }
    public record Evaluation(Optional<Signal> signal, String reason, String dataContext, Double vwap) {
        public Evaluation(Optional<Signal> signal,String reason){this(signal,reason,"",null);}
    }
    private static Evaluation waiting(String reason){return new Evaluation(Optional.empty(),reason);}
    private NaturalGasSignals() { }
    public static Optional<Signal> evaluate(List<StrategyCandle> input, LocalDateTime now, double tick) {
        return inspect(input,now,tick).signal();
    }
    public static Evaluation inspect(List<StrategyCandle> input, LocalDateTime now, double tick) {
        return inspect(input,now,tick,NaturalGasDataMode.FULL_SESSION);
    }
    public static Evaluation inspect(List<StrategyCandle> input, LocalDateTime now, double tick, NaturalGasDataMode mode) {
        return inspect(input,now,tick,mode,NaturalGasTrendTimeframe.FIFTEEN_MINUTE);
    }
    public static Evaluation inspect(List<StrategyCandle> input, LocalDateTime now, double tick, NaturalGasDataMode mode, NaturalGasTrendTimeframe trendTimeframe) {
        Objects.requireNonNull(mode,"MCX data mode");
        Objects.requireNonNull(trendTimeframe,"MCX trend timeframe");
        var result=inspectData(input,now,tick,mode,trendTimeframe);
        String context="trendTimeframe="+trendTimeframe+"; EMA20/50="+(trendTimeframe==NaturalGasTrendTimeframe.FIVE_MINUTE
                ?"available completed 5m bars, missing intervals omitted":"available complete 15m bars, missing groups omitted");
        if(!result.dataContext().isBlank())context+="; "+result.dataContext();
        if(mode==NaturalGasDataMode.ROLLING_WINDOW)context="dataMode=ROLLING_WINDOW; rolling VWAP=24 completed 5m bars; "+context;
        if(mode==NaturalGasDataMode.ROLLING_WINDOW && input!=null && now!=null) {
            var completed=input.stream().filter(c->valid(c)&&c.date().equals(now.toLocalDate())&&!at(c).plusMinutes(5).isAfter(now))
                    .sorted(Comparator.comparing(NaturalGasSignals::at)).toList();
            if(!completed.isEmpty()) {
                var window=completed.subList(Math.max(0,completed.size()-24),completed.size());
                context+="; window="+at(window.getFirst())+"/"+at(window.getLast()).plusMinutes(5)+" ("+window.size()+" bars)";
            }
        }
        if(result.vwap()!=null)context+="; rollingVWAPValue="+result.vwap();
        return new Evaluation(result.signal(),result.reason(),context,result.vwap());
    }
    private static Evaluation inspectData(List<StrategyCandle> input, LocalDateTime now, double tick, NaturalGasDataMode mode, NaturalGasTrendTimeframe trendTimeframe) {
        if (!NaturalGasSession.entryAllowed(now)) return waiting("Outside MCX entry window 09:00-23:00 IST");
        if (!Double.isFinite(tick) || tick <= 0) return waiting("Invalid MCX futures tick metadata");
        if(input==null || input.isEmpty())return waiting("No MCX futures candles available; waiting for historical seed and live session capture");
        var latest=input.stream().filter(c->c!=null&&c.date()!=null&&c.time()!=null)
                .map(c->LocalDateTime.of(c.date(),c.time())).max(LocalDateTime::compareTo).orElse(null);
        if(input.stream().noneMatch(c->c!=null&&now.toLocalDate().equals(c.date())))
            return waiting("No MCX candles for today; latest available="+latest+". Historical API excludes today; live session capture is required");
        TreeMap<LocalDateTime, StrategyCandle> ordered = new TreeMap<>();
        for (var c : input) {
            if (!valid(c)) return waiting("Invalid MCX candle OHLC or interval timestamp: "+(c==null?"null":c.date()+" "+c.time()));
            var start = LocalDateTime.of(c.date(), c.time());
            if (start.plusMinutes(5).isAfter(now)) continue;
            if (ordered.put(start, c) != null) return waiting("Duplicate MCX candle interval: "+start);
        }
        var bars = new ArrayList<>(ordered.values());
        int minimumHistory=trendTimeframe==NaturalGasTrendTimeframe.FIVE_MINUTE?53:153;
        if (bars.size() < minimumHistory) return waiting("Insufficient completed MCX history: "+bars.size()+"/"+minimumHistory+" five-minute candles");
        var today = bars.stream().filter(c -> c.date().equals(now.toLocalDate())).toList();
        if(today.isEmpty())return waiting("No completed MCX candles for today; waiting for live capture");
        if(mode==NaturalGasDataMode.FULL_SESSION) {
            if(!today.get(0).time().equals(NaturalGasSession.OPEN))return waiting("Incomplete MCX session: first captured interval="+today.get(0).time()+", required=09:00; missing opening candles cannot be reconstructed");
            if(today.size()<24)return waiting("MCX session warmup: "+today.size()+"/24 completed five-minute candles; earliest entry 11:00 IST");
        } else {
            today=today.subList(Math.max(0,today.size()-24),today.size());
            int consecutive=1;
            for(int i=today.size()-1;i>0&&at(today.get(i)).equals(at(today.get(i-1)).plusMinutes(5));i--)consecutive++;
            if(consecutive<24)return waiting("MCX rolling warmup: "+consecutive+"/24 consecutive completed five-minute candles; earliest readiness="+at(today.get(today.size()-consecutive)).plusHours(2));
        }
        for (int i=1;i<today.size();i++) if (!at(today.get(i)).equals(at(today.get(i-1)).plusMinutes(5))) return waiting("Gap in MCX session candles after "+at(today.get(i-1))+"; waiting for complete data");
        var last = today.get(today.size()-1);
        var end = at(last).plusMinutes(5);
        if (end.isBefore(now.minusSeconds(75))) return waiting("Stale MCX candle: latest completed interval ends "+end+"; freshness limit 75 seconds");
        var trendCloses = new ArrayList<Double>();
        if(trendTimeframe==NaturalGasTrendTimeframe.FIVE_MINUTE) {
            bars.forEach(c->trendCloses.add(c.close()));
        } else {
            for (int i=0;i+2<bars.size();i++) {
                var a=bars.get(i);var b=bars.get(i+1);var c=bars.get(i+2);
                if (a.time().getMinute()%15==0 && at(b).equals(at(a).plusMinutes(5)) && at(c).equals(at(a).plusMinutes(10))) trendCloses.add(c.close());
            }
        }
        if (trendCloses.size()<53) return waiting("EMA warmup: "+trendCloses.size()+"/53 complete "+(trendTimeframe==NaturalGasTrendTimeframe.FIVE_MINUTE?"five":"fifteen")+"-minute candles");
        double fast=ema(trendCloses,20,trendCloses.size()), slow=ema(trendCloses,50,trendCloses.size());
        double priorFast=ema(trendCloses,20,trendCloses.size()-3);
        String vwapName=mode==NaturalGasDataMode.ROLLING_WINDOW?"rolling VWAP":"session VWAP";
        double totalVolume=0, weighted=0;
        for(var c:today) {
            if(c.volume()==null || c.volume()<=0) return waiting("Missing or zero MCX interval volume at "+at(c)+"; "+vwapName+" unavailable");
            totalVolume+=c.volume();weighted+=(c.high()+c.low()+c.close())/3*c.volume();
        }
        double vwap=weighted/totalVolume;
        String values="EMA20="+fast+"; EMA50="+slow+"; EMA20ThreeBarsAgo="+priorFast+"; EMA20SlopeChange="+(fast-priorFast)+"; futuresClose="+last.close();
        boolean bull=fast>slow && fast>priorFast && last.close()>vwap;
        boolean bear=fast<slow && fast<priorFast && last.close()<vwap;
        if(!bull && !bear) return new Evaluation(Optional.empty(),"MCX EMA20/50 trend, EMA20 slope and "+vwapName+" are not aligned",values,vwap);
        double atr=0;
        for(int i=today.size()-14;i<today.size();i++) {
            var c=today.get(i);double p=today.get(i-1).close();
            atr+=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-p),Math.abs(c.low()-p)))/14;
        }
        if(!Double.isFinite(atr)||atr<=0) return new Evaluation(Optional.empty(),"MCX ATR unavailable or non-positive",values,vwap);
        int n=today.size();
        // Latest bar must reject a prior 20-bar boundary, 1-3 bars after its breakout.
        for(int b=n-2;b>=Math.max(20,n-4);b--) {
            var breakout=today.get(b);
            double level=bull ? today.subList(b-20,b).stream().mapToDouble(StrategyCandle::high).max().orElseThrow()
                    : today.subList(b-20,b).stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
            boolean broke=bull ? breakout.close()>level : breakout.close()<level;
            boolean rejected=bull ? last.low()<=level && last.close()>level && last.close()>last.open()
                    : last.high()>=level && last.close()<level && last.close()<last.open();
            if(!broke || !rejected) continue;
            // Reject an intervening close through the broken boundary.
            boolean failed=false;
            for(int j=b+1;j<n-1;j++) if(bull?today.get(j).close()<level:today.get(j).close()>level) failed=true;
            if(failed) continue;
            double trigger=bull?last.high()+tick:last.low()-tick;
            double structure=bull?last.low()-.2*atr:last.high()+.2*atr;
            double risk=Math.max(atr,Math.abs(trigger-structure));
            if(risk>2*atr) continue;
            return new Evaluation(Optional.of(new Signal(bull?"CE":"PE",trigger,bull?trigger-risk:trigger+risk,atr,end)),"Qualified MCX breakout/retest",values,vwap);
        }
        return new Evaluation(Optional.empty(),"MCX trend aligned; waiting for a qualifying 20-bar breakout and 1-3-bar retest",values,vwap);
    }
    private static boolean valid(StrategyCandle c) {
        return c!=null && c.date()!=null && c.time()!=null && !c.time().isBefore(NaturalGasSession.OPEN)
                && c.time().isBefore(NaturalGasSession.CLOSE) && c.time().getMinute()%5==0 && c.time().getSecond()==0
                && c.time().getNano()==0 && Double.isFinite(c.open()) && Double.isFinite(c.high())
                && Double.isFinite(c.low()) && Double.isFinite(c.close()) && c.low()>0
                && c.low()<=Math.min(c.open(),c.close()) && c.high()>=Math.max(c.open(),c.close());
    }
    private static LocalDateTime at(StrategyCandle c){return LocalDateTime.of(c.date(),c.time());}
    private static double ema(List<Double> x,int p,int count){double v=x.subList(0,p).stream().mapToDouble(d->d).average().orElseThrow();for(int i=p;i<count;i++)v+=2d/(p+1)*(x.get(i)-v);return v;}
}
