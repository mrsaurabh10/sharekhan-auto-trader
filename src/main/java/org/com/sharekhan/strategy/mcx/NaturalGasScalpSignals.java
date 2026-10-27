package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;

/** Three-minute momentum close; five-minute trend; no second breakout or mandatory retest. */
public final class NaturalGasScalpSignals {
    private NaturalGasScalpSignals(){}
    private static NaturalGasSignals.Evaluation waiting(String reason){return new NaturalGasSignals.Evaluation(Optional.empty(),reason,"entryProfile=SCALP_3M; trend=5m; rolling VWAP=40 completed 3m bars",null);}
    public static NaturalGasSignals.Evaluation inspect(List<StrategyCandle> entry,List<StrategyCandle> trend,LocalDateTime now,double tick) {
        if(!NaturalGasSession.entryAllowed(now))return waiting("Outside MCX entry window 09:00-23:00 IST");
        if(!Double.isFinite(tick)||tick<=0)return waiting("Invalid MCX futures tick metadata");
        List<StrategyCandle> three,five;
        try {three=completed(entry,3,now,true);five=completed(trend,5,now,false);}
        catch(IllegalArgumentException invalid){return waiting(invalid.getMessage());}
        if(three.size()<40)return waiting("Scalp warmup: "+three.size()+"/40 current-day three-minute bars (two-hour window)");
        if(five.size()<53)return waiting("Scalp EMA warmup: "+five.size()+"/53 completed five-minute observations");
        var window=three.subList(three.size()-40,three.size());
        for(int i=1;i<window.size();i++)if(!at(window.get(i)).equals(at(window.get(i-1)).plusMinutes(3)))return waiting("Gap in scalp three-minute window; waiting for consecutive bars");
        var last=window.getLast();var end=at(last).plusMinutes(3);
        if(end.isBefore(now.minusSeconds(75)))return waiting("Waiting for a fresh completed three-minute candle; latest end="+end);
        // A 5m trend remains current between its expected closes; this does not relax quote freshness.
        var expected=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
        if(!at(five.getLast()).plusMinutes(5).equals(expected))return waiting("Waiting for current completed five-minute trend candle; expected end="+expected);
        double fast=ema(five,20,five.size()),slow=ema(five,50,five.size()),prior=ema(five,20,five.size()-3);
        double volume=0,weighted=0;
        for(var c:window) {
            if(c.volume()==null||c.volume()<=0)return waiting("Missing scalp interval volume; rolling VWAP unavailable");
            volume+=c.volume();weighted+=(c.high()+c.low()+c.close())/3*c.volume();
        }
        double vwap=weighted/volume;
        String context="entryProfile=SCALP_3M; trend=5m EMA20/50 available completed observations; no mandatory retest; breakoutLookback=10; rolling VWAP=40x3m"
                +"; EMA20="+fast+"; EMA50="+slow+"; EMA20ThreeBarsAgo="+prior+"; EMA20SlopeChange="+(fast-prior)+"; futuresClose="+last.close()
                +"; rollingVWAPValue="+vwap+"; entryWindow="+at(window.getFirst())+"/"+end;
        boolean bull=fast>slow&&fast>prior&&last.close()>vwap;
        boolean bear=fast<slow&&fast<prior&&last.close()<vwap;
        if(!bull&&!bear)return new NaturalGasSignals.Evaluation(Optional.empty(),"Scalp trend/slope/VWAP are not aligned",context,vwap);
        double atr=0;
        for(int i=window.size()-14;i<window.size();i++) {
            var c=window.get(i);double p=window.get(i-1).close();
            atr+=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-p),Math.abs(c.low()-p)))/14;
        }
        if(!Double.isFinite(atr)||atr<=0 || last.high()-last.low()>2*atr)return new NaturalGasSignals.Evaluation(Optional.empty(),"Scalp candle exceeds the two-ATR spike guard or ATR is unavailable",context,vwap);
        var priorBars=window.subList(window.size()-1-NaturalGasScalpPolicy.BREAKOUT_BARS,window.size()-1);
        double level=bull?priorBars.stream().mapToDouble(StrategyCandle::high).max().orElseThrow():priorBars.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        double trigger=bull?level+tick:level-tick;
        boolean breaks=bull?last.close()+1e-9>=trigger&&last.close()>last.open():last.close()-1e-9<=trigger&&last.close()<last.open();
        context+="; breakoutBoundary="+level+"; ATR14="+atr+"; signalEnd="+end;
        if(!breaks)return new NaturalGasSignals.Evaluation(Optional.empty(),"Scalp waiting for a completed three-minute close beyond the prior ten-bar boundary",context,vwap);
        var signal=new NaturalGasSignals.Signal(bull?"CE":"PE",trigger,bull?trigger-atr:trigger+atr,atr,end);
        return new NaturalGasSignals.Evaluation(Optional.of(signal),"Qualified three-minute momentum breakout (no retest required)",context,vwap);
    }
    private static List<StrategyCandle> completed(List<StrategyCandle> input,int interval,LocalDateTime now,boolean todayOnly) {
        if(input==null)throw new IllegalArgumentException("Scalp candle source unavailable");
        var rows=new TreeMap<LocalDateTime,StrategyCandle>();
        for(var c:input) {
            if(c==null||c.date()==null||c.time()==null||c.time().getMinute()%interval!=0||c.time().getSecond()!=0||c.time().getNano()!=0
                    ||c.time().isBefore(NaturalGasSession.OPEN)||!c.time().isBefore(NaturalGasSession.CLOSE)
                    ||!Double.isFinite(c.open())||!Double.isFinite(c.high())||!Double.isFinite(c.low())||!Double.isFinite(c.close())
                    ||c.low()<=0||c.low()>Math.min(c.open(),c.close())||c.high()<Math.max(c.open(),c.close()))throw new IllegalArgumentException("Invalid scalp candle OHLC/timestamp");
            if(todayOnly&&!c.date().equals(now.toLocalDate()))throw new IllegalArgumentException("Three-minute entry data must be from today");
            if(at(c).isAfter(now))throw new IllegalArgumentException("Future scalp candle timestamp");
            if(at(c).plusMinutes(interval).isAfter(now))continue;
            if(rows.put(at(c),c)!=null)throw new IllegalArgumentException("Duplicate scalp interval");
        }
        return List.copyOf(rows.values());
    }
    private static double ema(List<StrategyCandle> bars,int period,int count){double v=bars.subList(0,period).stream().mapToDouble(StrategyCandle::close).average().orElseThrow();for(int i=period;i<count;i++)v+=2d/(period+1)*(bars.get(i).close()-v);return v;}
    private static LocalDateTime at(StrategyCandle c){return LocalDateTime.of(c.date(),c.time());}
}
