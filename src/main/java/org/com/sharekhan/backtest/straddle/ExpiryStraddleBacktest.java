package org.com.sharekhan.backtest.straddle;

import org.com.sharekhan.strategy.StrategyCandle;
import org.com.sharekhan.strategy.straddle.ExpiryStraddleRules;
import java.time.*;
import java.util.*;

/** A spot movement study, NOT a simulation of CE+PE premium returns. No broker dependencies. */
public final class ExpiryStraddleBacktest {
    public record Observation(LocalDate date,String session,double entrySpot,double exitSpot,
                              double signedPoints,double absolutePoints,Double high,Double low,Double rangePoints,
                              Double maxUpFromEntry,Double maxDownFromEntry,boolean completeWindow) { }
    public record Skipped(LocalDate date,String session,String reason) { }
    public record Summary(String session,int observations,double meanSignedPoints,double meanAbsolutePoints,
                          double medianAbsolutePoints,double maxAbsolutePoints,int completeWindows,Double meanRangePoints) { }
    public record Report(String symbol,int lots,String unit,String calendarPolicy,LocalDate firstDate,LocalDate lastDate,
                         List<Observation> observations,List<Skipped> skipped,List<Summary> summaries) { }
    public Report run(String symbol,Integer lots,List<StrategyCandle> candles,List<LocalDate> expiries,String calendarPolicy) {
        symbol=ExpiryStraddleRules.symbol(symbol);int lotCount=ExpiryStraddleRules.lots(lots);
        if(candles==null || candles.isEmpty()) throw new IllegalArgumentException("No stored candles available");
        var bars=new TreeMap<LocalDateTime,StrategyCandle>();
        for(var candle:candles) {
            var time=LocalDateTime.of(candle.date(),candle.time());
            if(bars.putIfAbsent(time,candle)!=null) throw new IllegalArgumentException("Duplicate candle at "+time);
            if(!Double.isFinite(candle.open()) || candle.open()<=0 || !Double.isFinite(candle.high()) || !Double.isFinite(candle.low())
                    || !Double.isFinite(candle.close()) || candle.low()>Math.min(candle.open(),candle.close())
                    || candle.high()<Math.max(candle.open(),candle.close())) throw new IllegalArgumentException("Invalid OHLC at "+time);
        }
        LocalDate first=bars.firstKey().toLocalDate(),last=bars.lastKey().toLocalDate();
        var rows=new ArrayList<Observation>();var skipped=new ArrayList<Skipped>();
        for(LocalDate day:new TreeSet<>(expiries)) {
            if(day.isBefore(first)||day.isAfter(last)) continue;
            for(var session:ExpiryStraddleRules.SESSIONS) {
                var entry=day.atTime(session.entry());var exit=day.atTime(session.exit());
                var entryBar=bars.get(entry);var exitBar=bars.get(exit);
                if(entryBar==null || exitBar==null) {
                    skipped.add(new Skipped(day,session.id(),"Exact entry/exit candle missing; no price substitution or inferred holiday shift"));continue;
                }
                double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;boolean complete=true;
                // Exit uses the 10:30/14:30 OPEN; exclude that candle's subsequent high/low.
                for(var time=entry;time.isBefore(exit);time=time.plusMinutes(5)) {
                    var bar=bars.get(time);
                    if(bar==null) {complete=false;continue;}
                    high=Math.max(high,bar.high());low=Math.min(low,bar.low());
                }
                high=Math.max(high,exitBar.open());low=Math.min(low,exitBar.open());
                double change=exitBar.open()-entryBar.open();
                rows.add(new Observation(day,session.id(),entryBar.open(),exitBar.open(),change,Math.abs(change),
                        complete?high:null,complete?low:null,complete?high-low:null,
                        complete?high-entryBar.open():null,complete?entryBar.open()-low:null,complete));
            }
        }
        var summaries=new ArrayList<Summary>();
        for(String session:List.of("AM","PM","ALL")) {
            var selected=rows.stream().filter(r->session.equals("ALL")||session.equals(r.session())).toList();
            double[] absolute=selected.stream().mapToDouble(Observation::absolutePoints).sorted().toArray();
            int n=absolute.length;var complete=selected.stream().filter(Observation::completeWindow).toList();
            summaries.add(new Summary(session,n,selected.stream().mapToDouble(Observation::signedPoints).average().orElse(0),
                    Arrays.stream(absolute).average().orElse(0),n==0?0:(absolute[(n-1)/2]+absolute[n/2])/2,
                    n==0?0:absolute[n-1],complete.size(),complete.isEmpty()?null:complete.stream().mapToDouble(Observation::rangePoints).average().orElseThrow()));
        }
        return new Report(symbol,lotCount,"SPOT_INDEX_POINTS; not option P&L; not multiplied by lots",calendarPolicy,first,last,
                List.copyOf(rows),List.copyOf(skipped),List.copyOf(summaries));
    }
}
