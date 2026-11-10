package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import org.com.sharekhan.cache.QuoteCacheService;
import java.util.*;

/** Frozen Pine defaults. Indicators carry across sessions; the first DM/TR observation is undefined. */
public final class AdxDmiRules {
    private AdxDmiRules() { }
    public static final String TEMPLATE="ADX_DMI_FIXED_PARTIALS", SOURCE="strategy:"+TEMPLATE;
    public static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    public static final LocalTime CUTOFF=LocalTime.NOON, HARD_CUTOFF=LocalTime.of(14,50), SQUARE_OFF=LocalTime.of(15,20);
    public static final double TICK=.05, DELTA=.5;
    public static boolean owns(String source){return SOURCE.equalsIgnoreCase(source);}
    public static String optionExchange(String cashExchange){
        return switch(Objects.toString(cashExchange,"").toUpperCase(Locale.ROOT)){case "NC","NSE"->"NF";case "BC","BSE"->"BF";default->throw new IllegalArgumentException("Underlying requires a listed cash exchange");};
    }
    public static String cashExchange(String optionExchange){
        return switch(Objects.toString(optionExchange,"").toUpperCase(Locale.ROOT)){case "NF","NFO"->"NC";case "BF","BFO"->"BC";default->throw new IllegalArgumentException("Option requires a listed derivatives exchange");};
    }
    public static String symbol(String input){
        String key=Objects.toString(input, "").trim().toUpperCase(Locale.ROOT);
        if(key.isBlank() || key.length()>100 || key.indexOf('\n')>=0 || key.indexOf('\r')>=0 || key.indexOf(',')>=0)throw new IllegalArgumentException("A single underlying symbol is required");
        return key;
    }
    public static Optional<Signal> latestSignal(List<StrategyCandle> bars,LocalDateTime now){return latestSignal(bars,now,TICK);}
    public static Optional<Signal> latestSignal(List<StrategyCandle> bars,LocalDateTime now,double tickSize){
        if(bars.size()<200)return Optional.empty();
        var dmi=new Dmi();Reading reading=null;StrategyCandle latest=null;
        for(var bar:bars){if(start(bar).plusMinutes(5).isAfter(now))break;reading=dmi.add(bar);latest=bar;}
        if(latest==null || !latest.date().equals(now.toLocalDate()))return Optional.empty();
        return signal(latest,reading,true,tickSize).filter(s->s.inWindow(now));
    }
    public record Reading(double plus, double minus, double adx) {
        public int direction(){return adx>25 && plus>25 && minus<20?1:adx>25 && minus>25 && plus<20?-1:0;}
    }
    public record Signal(int direction, LocalDateTime end, double trigger, double stop, Reading reading, double tickSize) {
        public Signal(int direction,LocalDateTime end,double trigger,double stop,Reading reading){this(direction,end,trigger,stop,reading,TICK);}
        public Signal {if(direction!=1 && direction!=-1 || !Double.isFinite(tickSize) || tickSize<=0)throw new IllegalArgumentException("Invalid direction/tick metadata");}
        public String side(){return direction==1?"CE":"PE";}
        public boolean pe(){return direction==-1;}
        public String key(){return end+":"+side();}
        public boolean inWindow(LocalDateTime now){return !now.isBefore(end) && now.isBefore(end.plusMinutes(5)) && now.toLocalTime().isBefore(CUTOFF);}
        public boolean crossed(double spot){return Double.isFinite(spot) && spot>0 && direction*(spot-trigger)>=-1e-8;}
        public double risk(double entry){return direction*(entry-stop);}
        public double target(double entry,int leg){return tick(entry+direction*risk(entry)*leg,tickSize);}
    }
    public static LocalDateTime start(StrategyCandle bar){return LocalDateTime.of(bar.date(),bar.time());}
    public static double tick(double price){return tick(price,TICK);}
    public static double tick(double price,double tickSize){return Math.floor(price/tickSize+.5+1e-9)*tickSize;}
    public static boolean entryWindow(LocalDateTime close,boolean morningOnly){
        return !close.toLocalTime().isBefore(LocalTime.of(9,15)) && close.toLocalTime().isBefore(morningOnly?CUTOFF:HARD_CUTOFF);
    }
    public static Optional<Signal> signal(StrategyCandle bar,Reading reading,boolean morningOnly){return signal(bar,reading,morningOnly,TICK);}
    public static Optional<Signal> signal(StrategyCandle bar,Reading reading,boolean morningOnly,double tickSize){
        int direction=reading.direction();var end=start(bar).plusMinutes(5);
        if(direction==0 || bar.high()<=bar.low() || !entryWindow(end,morningOnly))return Optional.empty();
        return Optional.of(new Signal(direction,end,tick(direction==1?bar.high()+tickSize:bar.low()-tickSize,tickSize),direction==1?bar.low():bar.high(),reading,tickSize));
    }
    public static void validate(StrategyCandle bar){
        if(bar==null || bar.date()==null || bar.time()==null || !Double.isFinite(bar.open()) || !Double.isFinite(bar.high())
                || !Double.isFinite(bar.low()) || !Double.isFinite(bar.close()) || bar.low()<=0
                || bar.low()>Math.min(bar.open(),bar.close()) || bar.high()<Math.max(bar.open(),bar.close()))
            throw new IllegalArgumentException("Invalid spot candle");
        if(bar.time().isBefore(LocalTime.of(9,15)) || !bar.time().isBefore(LocalTime.of(15,30)) || bar.time().getMinute()%5!=0
                || bar.time().getSecond()!=0 || bar.time().getNano()!=0)throw new IllegalArgumentException("Expected regular-session IST five-minute start stamps");
    }
    /** Wilder RMA seeded with SMA, matching ta.dmi(14,14), including flat-series DX=0. */
    public static final class Dmi {
        private StrategyCandle previous;
        private final Rma tr=new Rma(),plus=new Rma(),minus=new Rma(),adx=new Rma();
        private double previousPlus=Double.NaN,previousMinus=Double.NaN;
        public Reading add(StrategyCandle bar){
            validate(bar);
            if(previous!=null && !start(bar).isAfter(start(previous)))throw new IllegalArgumentException("Candles must be strictly chronological");
            double p=Double.NaN,m=Double.NaN,a=Double.NaN;
            if(previous!=null){
                double up=bar.high()-previous.high(),down=previous.low()-bar.low();
                double range=tr.add(Math.max(bar.high()-bar.low(),Math.max(Math.abs(bar.high()-previous.close()),Math.abs(bar.low()-previous.close()))));
                double pdm=plus.add(up>down && up>0?up:0),mdm=minus.add(down>up && down>0?down:0);
                if(Double.isFinite(range)){
                    p=range==0?previousPlus:100*pdm/range;m=range==0?previousMinus:100*mdm/range;
                    previousPlus=p;previousMinus=m;
                    if(Double.isFinite(p) && Double.isFinite(m))a=100*adx.add(Math.abs(p-m)/(p+m==0?1:p+m));
                }
            }
            previous=bar;return new Reading(p,m,a);
        }
    }
    private static final class Rma {
        private int count;private double sum,value=Double.NaN;
        double add(double x){if(!Double.isFinite(x))return value;if(count<14){sum+=x;if(++count==14)value=sum/14;}else value=(13*value+x)/14;return value;}
    }
    public static boolean freshSpot(QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return q!=null && Objects.equals(q.getScripCode(),code) && q.getLastTradedPrice()!=null && Double.isFinite(q.getLastTradedPrice())
                && q.getLastTradedPrice()>0 && fresh(q.getLastLtpAt(),now) && "SHAREKHAN_WS".equals(q.getLtpSource())
                && Set.of("NC","NSE","BC","BSE").contains(Objects.toString(q.getLtpExchange(),"")) && ("scrip:"+code).equals(q.getLtpProviderIdentity());
    }
    public static boolean freshSpot(String cashExchange,QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return freshSpot(q,now,code) && ("NC".equals(cashExchange)?Set.of("NC","NSE"):Set.of("BC","BSE")).contains(q.getLtpExchange());
    }
    public static boolean freshBook(String exchange,QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return freshBook(q,now,code) && ("NF".equals(exchange)?Set.of("NF","NFO"):Set.of("BF","BFO")).contains(q.getBookExchange());
    }
    public static boolean freshBook(QuoteCacheService.QuoteSnapshot q,Instant now,int code){
        return q!=null && Objects.equals(q.getScripCode(),code) && q.hasBook() && q.isCoherentBook()
                && Double.isFinite(q.getBestBid()) && Double.isFinite(q.getBestAsk()) && q.getBestBid()<=q.getBestAsk()
                && fresh(q.getLastBookAt(),now) && "SHAREKHAN_WS".equals(q.getBookSource())
                && Set.of("NF","NFO","BF","BFO").contains(Objects.toString(q.getBookExchange(),"")) && ("scrip:"+code).equals(q.getBookProviderIdentity());
    }
    private static boolean fresh(Instant at,Instant now){return at!=null && !at.isAfter(now) && !at.isBefore(now.minusSeconds(5));}
}
