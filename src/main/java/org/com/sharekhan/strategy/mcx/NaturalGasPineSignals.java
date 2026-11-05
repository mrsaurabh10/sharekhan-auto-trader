package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.strategy.StrategyCandle;

/** EMA9/21, daily HLC3 VWAP, confirmed hourly EMA50 and Wilder ATR14/SMA20. */
public final class NaturalGasPineSignals {
    private NaturalGasPineSignals() { }
    public record Snapshot(boolean ready, String side, LocalDateTime end, double close,
                           double slow, double vwap, double atr, String reason) {
        public boolean fresh(LocalDateTime now) {return end!=null && !end.isAfter(now) && !end.isBefore(now.minusSeconds(75));}
        public boolean invalidated(String heldSide) {
            return ready && ("CE".equals(heldSide)?close<slow || close<vwap:close>slow || close>vwap);
        }
    }
    public static Snapshot inspect(List<StrategyCandle> input, LocalDateTime now, NaturalGasPinePolicy policy) {
        if(input==null || input.isEmpty())return waitFor("No futures candles available");
        var map=new TreeMap<LocalDateTime,StrategyCandle>();
        for(var c:input) {
            if(!valid(c))return waitFor("Invalid futures OHLC or five-minute timestamp");
            if(at(c).plusMinutes(5).isAfter(now))continue;
            if(map.put(at(c),c)!=null)return waitFor("Duplicate futures interval");
        }
        var bars=List.copyOf(map.values());
        if(bars.size()<34)return waitFor("Waiting for ATR14 / SMA20 warmup");
        var today=bars.stream().filter(c->c.date().equals(now.toLocalDate())).toList();
        if(today.isEmpty() || !today.getFirst().time().equals(NaturalGasSession.OPEN))return waitFor("Full 09:00 IST session required for VWAP");
        for(int i=1;i<today.size();i++)if(!at(today.get(i)).equals(at(today.get(i-1)).plusMinutes(5)))return waitFor("Gap in today's futures session");
        if(today.stream().anyMatch(c->c.volume()==null || c.volume()<=0))return waitFor("Missing or zero session volume; VWAP unavailable");
        var last=bars.getLast();
        if(!last.date().equals(now.toLocalDate()))return waitFor("No completed futures candle today");
        // The HTF value known at the OPEN of the signal candle matches Pine [1] + lookahead_on.
        var hourly=hourlyCloses(map,at(last));
        if(hourly.size()<50)return waitFor("Hourly EMA50 warmup: "+hourly.size()+"/50 complete hours; no partial-hour substitution");
        double htf=ema(hourly,50)[hourly.size()-1];
        var closes=bars.stream().map(StrategyCandle::close).toList();
        double[] fast=ema(closes,9),slow=ema(closes,21),atr=atr(bars);
        int n=bars.size()-1;
        double average=0;
        for(int i=n-19;i<=n;i++)average+=atr[i]/20;
        double weighted=0,volume=0;
        for(var c:today){weighted+=(c.high()+c.low()+c.close())/3*c.volume();volume+=c.volume();}
        double vwap=weighted/volume;
        boolean up=fast[n]>slow[n] && last.close()>vwap && last.close()>htf && atr[n]>average*1.1;
        boolean down=fast[n]<slow[n] && last.close()<vwap && last.close()<htf && atr[n]>average*1.1;
        boolean bull=fast[n]>slow[n] && fast[n-1]<=slow[n-1];
        boolean bear=fast[n]<slow[n] && fast[n-1]>=slow[n-1];
        var prev=bars.get(n-1);
        if(policy.getEntryMode()==NaturalGasPinePolicy.EntryMode.PULLBACK) {
            bull=fast[n-1]>slow[n-1] && prev.low()<=fast[n-1] && prev.high()>=slow[n-1] && prev.close()>=slow[n-1]
                    && last.close()>prev.high() && last.close()>fast[n] && last.close()-fast[n]<=atr[n];
            bear=fast[n-1]<slow[n-1] && prev.high()>=fast[n-1] && prev.low()<=slow[n-1] && prev.close()<=slow[n-1]
                    && last.close()<prev.low() && last.close()<fast[n] && fast[n]-last.close()<=atr[n];
        }
        String side=up && bull?"CE":down && bear?"PE":null;
        return new Snapshot(true,side,at(last).plusMinutes(5),last.close(),slow[n],vwap,atr[n],
                "mode="+policy.getEntryMode()+"; EMA9="+fast[n]+"; EMA21="+slow[n]+"; VWAP="+vwap+"; confirmedHourlyEMA50="+htf+"; ATR="+atr[n]+"; ATRaverage="+average);
    }
    static List<Double> hourlyCloses(NavigableMap<LocalDateTime,StrategyCandle> map, LocalDateTime knownAt) {
        var result=new ArrayList<Double>();
        for(var start:map.keySet()) {
            if(start.getMinute()!=0 || start.plusHours(1).isAfter(knownAt))continue;
            boolean complete=true;
            for(int j=0;j<12;j++)if(!map.containsKey(start.plusMinutes(j*5L))){complete=false;break;}
            if(complete)result.add(map.get(start.plusMinutes(55)).close());
        }
        return result;
    }
    static double[] ema(List<Double> closes,int period) {
        double[] out=new double[closes.size()];out[0]=closes.getFirst();
        for(int i=1;i<out.length;i++)out[i]=out[i-1]+2d/(period+1)*(closes.get(i)-out[i-1]);
        return out;
    }
    static double[] atr(List<StrategyCandle> bars) {
        double[] out=new double[bars.size()];Arrays.fill(out,Double.NaN);double seed=0;
        for(int i=0;i<bars.size();i++) {
            var c=bars.get(i);double prev=i==0?c.close():bars.get(i-1).close();
            double tr=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-prev),Math.abs(c.low()-prev)));
            if(i<14)seed+=tr;
            if(i==13)out[i]=seed/14;
            else if(i>13)out[i]=(out[i-1]*13+tr)/14;
        }
        return out;
    }
    private static boolean valid(StrategyCandle c) {
        return c!=null && c.date()!=null && c.time()!=null && c.time().getMinute()%5==0 && c.time().getSecond()==0 && c.time().getNano()==0
                && !c.time().isBefore(NaturalGasSession.OPEN) && c.time().isBefore(NaturalGasSession.CLOSE)
                && Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low()) && Double.isFinite(c.close())
                && c.low()>0 && c.low()<=Math.min(c.open(),c.close()) && c.high()>=Math.max(c.open(),c.close());
    }
    private static LocalDateTime at(StrategyCandle c){return LocalDateTime.of(c.date(),c.time());}
    private static Snapshot waitFor(String reason){return new Snapshot(false,null,null,0,0,0,0,reason);}
}
