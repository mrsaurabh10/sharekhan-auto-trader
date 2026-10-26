package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;

/** Completed candles only. Missing volume, incomplete fifteen-minute groups and gaps fail closed. */
public final class NaturalGasSignals {
    public record Signal(String side, double trigger, double stop, double atr, LocalDateTime candleEnd) { }
    public record Evaluation(Optional<Signal> signal, String reason) { }
    private static Evaluation waiting(String reason){return new Evaluation(Optional.empty(),reason);}
    private NaturalGasSignals() { }
    public static Optional<Signal> evaluate(List<StrategyCandle> input, LocalDateTime now, double tick) {
        return inspect(input,now,tick).signal();
    }
    public static Evaluation inspect(List<StrategyCandle> input, LocalDateTime now, double tick) {
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
        if (bars.size() < 153) return waiting("Insufficient completed MCX history: "+bars.size()+"/153 five-minute candles");
        var today = bars.stream().filter(c -> c.date().equals(now.toLocalDate())).toList();
        if(today.isEmpty())return waiting("No completed MCX candles for today; waiting for live capture");
        if(!today.get(0).time().equals(NaturalGasSession.OPEN))return waiting("Incomplete MCX session: first captured interval="+today.get(0).time()+", required=09:00; missing opening candles cannot be reconstructed");
        if(today.size()<24)return waiting("MCX session warmup: "+today.size()+"/24 completed five-minute candles; earliest entry 11:00 IST");
        for (int i=1;i<today.size();i++) if (!at(today.get(i)).equals(at(today.get(i-1)).plusMinutes(5))) return waiting("Gap in MCX session candles after "+at(today.get(i-1))+"; waiting for complete data");
        var last = today.get(today.size()-1);
        var end = at(last).plusMinutes(5);
        if (end.isBefore(now.minusSeconds(75))) return waiting("Stale MCX candle: latest completed interval ends "+end+"; freshness limit 75 seconds");
        var fifteen = new ArrayList<Double>();
        for (int i=0;i+2<bars.size();i++) {
            var a=bars.get(i);var b=bars.get(i+1);var c=bars.get(i+2);
            if (a.time().getMinute()%15==0 && at(b).equals(at(a).plusMinutes(5)) && at(c).equals(at(a).plusMinutes(10))) fifteen.add(c.close());
        }
        if (fifteen.size()<53) return waiting("EMA warmup: "+fifteen.size()+"/53 complete fifteen-minute candles");
        double fast=ema(fifteen,20,fifteen.size()), slow=ema(fifteen,50,fifteen.size());
        double priorFast=ema(fifteen,20,fifteen.size()-3);
        double totalVolume=0, weighted=0;
        for(var c:today) {
            if(c.volume()==null || c.volume()<=0) return waiting("Missing or zero MCX interval volume at "+at(c)+"; session VWAP unavailable");
            totalVolume+=c.volume();weighted+=(c.high()+c.low()+c.close())/3*c.volume();
        }
        double vwap=weighted/totalVolume;
        boolean bull=fast>slow && fast>priorFast && last.close()>vwap;
        boolean bear=fast<slow && fast<priorFast && last.close()<vwap;
        if(!bull && !bear) return waiting("MCX EMA20/50 trend, EMA20 slope and session VWAP are not aligned");
        double atr=0;
        for(int i=today.size()-14;i<today.size();i++) {
            var c=today.get(i);double p=today.get(i-1).close();
            atr+=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-p),Math.abs(c.low()-p)))/14;
        }
        if(!Double.isFinite(atr)||atr<=0) return waiting("MCX ATR unavailable or non-positive");
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
            return new Evaluation(Optional.of(new Signal(bull?"CE":"PE",trigger,bull?trigger-risk:trigger+risk,atr,end)),"Qualified MCX breakout/retest");
        }
        return waiting("MCX trend aligned; waiting for a qualifying 20-bar breakout and 1-3-bar retest");
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
