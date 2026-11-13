package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.strategy.StrategyCandle;
import org.com.sharekhan.cache.QuoteCacheService;
import java.time.*;
import java.util.*;

/** Completed spot candles only. CE is the frozen research rule; PE mirrors it. */
public final class NiftyThreeCandleRules {
    private NiftyThreeCandleRules() { }
    public static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    public static final String TEMPLATE="NIFTY_THREE_CANDLE_ATM", SOURCE="strategy:"+TEMPLATE;
    public static final double DAILY_LOSS=5000;
    public static boolean owns(String source) { return SOURCE.equalsIgnoreCase(source); }
    public record Signal(String side, LocalDateTime end, double trigger, double stop, double ema, double priorEma) {
        public boolean pe(){return "PE".equals(side);}
        public boolean inWindow(LocalDateTime now){return !now.isBefore(end) && now.isBefore(end.plusMinutes(5)) && now.toLocalTime().isBefore(LocalTime.of(15,30));}
        public boolean crossed(double price){return Double.isFinite(price) && price>0 && (pe()?price<=trigger:price>=trigger);}
        public double target(double entry){return pe()?entry-(stop-entry):entry+(entry-stop);}
        public String key(){return end+":"+side;}
    }
    public record SignalCheck(Signal signal, String reason) { }
    public static Optional<Signal> signal(List<StrategyCandle> input,LocalDateTime now) {
        return Optional.ofNullable(check(input,now).signal());
    }
    public static SignalCheck check(List<StrategyCandle> input,LocalDateTime now) {
        var sorted=new TreeMap<LocalDateTime,StrategyCandle>();
        for(var c:input) {
            if(c==null || c.date()==null || c.time()==null)continue;
            var start=LocalDateTime.of(c.date(),c.time());
            if(start.plusMinutes(5).isAfter(now))continue;
            if(c.time().isBefore(LocalTime.of(9,15)) || !c.time().isBefore(LocalTime.of(15,30)) || c.time().getMinute()%5!=0 || c.time().getSecond()!=0)continue;
            if(!valid(c))return new SignalCheck(null,"Invalid completed candle at "+start);
            var old=sorted.putIfAbsent(start,c);
            if(old!=null && !old.equals(c))return new SignalCheck(null,"Conflicting completed candles at "+start);
        }
        var bars=new ArrayList<>(sorted.values());
        // 200 observations make EMA20 seeding error negligible; never start from today's three bars.
        if(bars.size()<200)return new SignalCheck(null,"Indicator warmup incomplete: "+bars.size()+"/200 completed candles");
        var today=bars.stream().filter(c->c.date().equals(now.toLocalDate())).toList();
        for(int i=0;i<today.size();i++)if(!today.get(i).time().equals(LocalTime.of(9,15).plusMinutes(5L*i)))return new SignalCheck(null,"Today has a candle gap; expected "+LocalTime.of(9,15).plusMinutes(5L*i));
        int n=bars.size();var a=bars.get(n-3);var b=bars.get(n-2);var c=bars.get(n-1);
        var end=LocalDateTime.of(c.date(),c.time()).plusMinutes(5);
        if(!c.date().equals(now.toLocalDate()) || !a.date().equals(c.date()) || !b.date().equals(c.date())
                || !a.time().plusMinutes(5).equals(b.time()) || !b.time().plusMinutes(5).equals(c.time()))return new SignalCheck(null,"Waiting for three consecutive completed candles today");
        boolean ce=a.close()>a.open() && b.close()>b.open() && c.close()>c.open();
        boolean pe=a.close()<a.open() && b.close()<b.open() && c.close()<c.open();
        if(!ce && !pe)return new SignalCheck(null,"Latest three completed candles are not all green or all red; end="+end);
        double ema=bars.getFirst().close(),prior=Double.NaN;
        for(int i=1;i<n;i++) {
            ema+=2d/21*(bars.get(i).close()-ema);
            if(i==n-4)prior=ema;
        }
        if(!(ce?ema>prior:ema<prior))return new SignalCheck(null,"EMA20 direction does not match "+(ce?"CE":"PE")+" pattern; end="+end);
        var s=new Signal(ce?"CE":"PE",end,Math.round((ce?c.high()+.05:c.low()-.05)*100)/100d,ce?c.low():c.high(),ema,prior);
        return s.inWindow(now)?new SignalCheck(s,null):new SignalCheck(null,"The fourth-candle entry window has expired; signal="+s.key());
    }
    private static boolean valid(StrategyCandle c){
        return Double.isFinite(c.open()) && Double.isFinite(c.close()) && Double.isFinite(c.high()) && Double.isFinite(c.low())
                && c.low()>0 && c.low()<=Math.min(c.open(),c.close()) && c.high()>=Math.max(c.open(),c.close());
    }
    public static boolean freshSpot(QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return q!=null && Objects.equals(q.getScripCode(),code) && q.getLastTradedPrice()!=null && Double.isFinite(q.getLastTradedPrice())
                && q.getLastTradedPrice()>0 && fresh(q.getLastLtpAt(),now) && "SHAREKHAN_WS".equals(q.getLtpSource())
                && Set.of("NC","NSE").contains(Objects.toString(q.getLtpExchange(),"")) && ("scrip:"+code).equals(q.getLtpProviderIdentity());
    }
    public static boolean freshBook(QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return q!=null && Objects.equals(q.getScripCode(),code) && q.hasBook() && q.isCoherentBook()
                && Double.isFinite(q.getBestBid()) && Double.isFinite(q.getBestAsk()) && q.getBestBid()<=q.getBestAsk()
                && fresh(q.getLastBookAt(),now) && "SHAREKHAN_WS".equals(q.getBookSource())
                && Set.of("NF","NFO").contains(Objects.toString(q.getBookExchange(),"")) && ("scrip:"+code).equals(q.getBookProviderIdentity());
    }
    private static boolean fresh(Instant at,Instant now){return at!=null && !at.isAfter(now) && !at.isBefore(now.minusSeconds(5));}
}
