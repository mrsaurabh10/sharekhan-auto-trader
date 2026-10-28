package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.strategy.StrategyCandle;

/** Closed futures bars only. A weakening trend pauses additions; a 15m EMA cross exits. */
public final class NaturalGasAveragingSignals {
    private NaturalGasAveragingSignals() { }
    public record Snapshot(boolean trendReady, boolean healthy, String side, boolean alignedFive,
                           boolean pullback, LocalDateTime signalEnd, double close, double atr,
                           List<StrategyCandle> five, String context) {
        public boolean reversed(String heldSide) {return trendReady && !Objects.equals(side,heldSide);}
        public OptionalDouble previousFillClose(LocalDateTime entryAt) {
            if(entryAt==null) return OptionalDouble.empty();
            var end=entryAt.withMinute(entryAt.getMinute()/5*5).withSecond(0).withNano(0);
            return five.stream().filter(c->at(c).plusMinutes(5).equals(end)).mapToDouble(StrategyCandle::close).findFirst();
        }
        public boolean fresh(LocalDateTime now) {
            return signalEnd!=null && !signalEnd.isAfter(now) && !signalEnd.isBefore(now.minusSeconds(75));
        }
    }
    public static Snapshot inspect(List<StrategyCandle> input,LocalDateTime now) {
        try {
            var map=new TreeMap<LocalDateTime,StrategyCandle>();
            for(var c:Objects.requireNonNull(input)) {
                if(c==null || c.date()==null || c.time()==null || c.time().getMinute()%5!=0 || c.time().getSecond()!=0
                        || c.time().getNano()!=0 || c.time().isBefore(NaturalGasSession.OPEN) || !c.time().isBefore(NaturalGasSession.CLOSE)
                        || !Double.isFinite(c.open()) || !Double.isFinite(c.high()) || !Double.isFinite(c.low()) || !Double.isFinite(c.close())
                        || c.low()<=0 || c.low()>Math.min(c.open(),c.close()) || c.high()<Math.max(c.open(),c.close())
                        || at(c).isAfter(now)) return waiting("Invalid five-minute futures bar");
                if(at(c).plusMinutes(5).isAfter(now))continue;
                if(map.put(at(c),c)!=null)return waiting("Duplicate five-minute futures bar");
            }
            var five=List.copyOf(map.values());var fifteen=aggregate(five);
            var expected15=now.withMinute(now.getMinute()/15*15).withSecond(0).withNano(0);
            if(fifteen.size()<24 || !at(fifteen.getLast()).plusMinutes(15).equals(expected15)
                    || !fifteen.getLast().date().equals(now.toLocalDate()))return waiting("Waiting for 24 completed fifteen-minute observations and today's latest 15m close");
            double fast=ema(fifteen,9,fifteen.size()),slow=ema(fifteen,21,fifteen.size()),prior=ema(fifteen,21,fifteen.size()-3);
            String side=fast>slow?"CE":fast<slow?"PE":"FLAT";
            boolean bull="CE".equals(side);
            boolean healthy=bull?slow>prior && fifteen.getLast().close()>slow:"PE".equals(side) && slow<prior && fifteen.getLast().close()<slow;
            String context="15m EMA9="+fast+"; EMA21="+slow+"; EMA21Slope="+(slow-prior)+"; trendEnd="+expected15;
            var last=five.getLast();var end=at(last).plusMinutes(5);
            var expected5=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
            if(five.size()<24 || !end.equals(expected5))return new Snapshot(true,healthy,side,false,false,end,last.close(),0,five,context+"; waiting for latest five-minute close");
            for(int i=five.size()-14;i<five.size();i++)
                if(five.get(i).date().equals(five.get(i-1).date()) && !at(five.get(i)).equals(at(five.get(i-1)).plusMinutes(5)))return new Snapshot(true,healthy,side,false,false,end,last.close(),0,five,context+"; gap in recent five-minute bars");
            double e9=ema(five,9,five.size()),e21=ema(five,21,five.size()),old21=ema(five,21,five.size()-3),atr=0;
            for(int i=five.size()-14;i<five.size();i++) {var c=five.get(i);double p=five.get(i-1).close();atr+=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-p),Math.abs(c.low()-p)))/14;}
            boolean aligned=bull?e9>e21 && e21>old21 && last.close()>=e21:"PE".equals(side) && e9<e21 && e21<old21 && last.close()<=e21;
            boolean pullback=aligned && (bull?last.low()<=e9:last.high()>=e9);
            return new Snapshot(true,healthy,side,aligned,pullback,end,last.close(),atr,five,
                    context+"; 5m EMA9="+e9+"; EMA21="+e21+"; close="+last.close()+"; ATR14="+atr+"; signalEnd="+end);
        } catch(RuntimeException invalid) {return waiting("Futures trend data unavailable");}
    }
    static List<StrategyCandle> aggregate(List<StrategyCandle> rows) {
        var byTime=new HashMap<LocalDateTime,StrategyCandle>();for(var c:rows)byTime.put(at(c),c);
        var result=new ArrayList<StrategyCandle>();
        for(var a:rows) {
            var t=at(a);if(t.getMinute()%15!=0)continue;
            var b=byTime.get(t.plusMinutes(5));var c=byTime.get(t.plusMinutes(10));
            if(b==null || c==null || !a.date().equals(c.date()))continue;
            Long volume=a.volume()==null || b.volume()==null || c.volume()==null?null:a.volume()+b.volume()+c.volume();
            result.add(new StrategyCandle(a.date(),a.time(),a.open(),Math.max(a.high(),Math.max(b.high(),c.high())),
                    Math.min(a.low(),Math.min(b.low(),c.low())),c.close(),volume));
        }
        return List.copyOf(result);
    }
    private static double ema(List<StrategyCandle> rs,int n,int count) {
        double e=rs.subList(0,n).stream().mapToDouble(StrategyCandle::close).average().orElseThrow();
        for(int i=n;i<count;i++)e+=(rs.get(i).close()-e)*2/(n+1);return e;
    }
    private static Snapshot waiting(String message){return new Snapshot(false,false,"FLAT",false,false,null,0,0,List.of(),message);}
    private static LocalDateTime at(StrategyCandle c){return LocalDateTime.of(c.date(),c.time());}
}
