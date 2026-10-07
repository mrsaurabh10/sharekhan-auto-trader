package org.com.sharekhan.backtest.csv;

import org.com.sharekhan.strategy.*;
import java.time.*;
import java.util.*;

/** Pure spot simulation. Never invokes an evaluator capable of sending orders. */
public class SupertrendCsvBacktest {
    private final IndicatorService indicators;
    private final SupertrendSignalRules rules;
    public SupertrendCsvBacktest(IndicatorService indicators,SupertrendSignalRules rules) { this.indicators=indicators;this.rules=rules; }
    public record Config(String symbol,LocalDate from,LocalDate to,double targetR,double slippagePoints,double costPoints,LocalTime squareOff) {
        public Config {
            if (!Set.of("NIFTY","BANKNIFTY").contains(symbol)) throw new IllegalArgumentException("symbol must be NIFTY or BANKNIFTY");
            if (from!=null&&to!=null&&from.isAfter(to)) throw new IllegalArgumentException("from must not exceed to");
            if (!Double.isFinite(targetR)||targetR<=0||!Double.isFinite(slippagePoints)||slippagePoints<0||!Double.isFinite(costPoints)||costPoints<0)
                throw new IllegalArgumentException("Invalid target, slippage or costs");
            if (squareOff==null||squareOff.isBefore(LocalTime.of(9,30))||squareOff.isAfter(LocalTime.of(15,30))) throw new IllegalArgumentException("Invalid square-off time");
        }
    }
    public record Trade(String direction,LocalDateTime signalAt,LocalDateTime entryAt,LocalDateTime exitBarAt,
                        double entry,double stop,double target,double exit,String exitReason,double netPoints,double netR) { }
    public record Summary(String direction,int trades,int wins,double winRate,double netPoints,double averageR,Double profitFactor,double maxDrawdownPoints) { }
    public record DataQuality(long rows,long outsideSession,long incompleteGroups,long missingFiveMinuteSlots,long normalizedTimestamps,int candles,LocalDate firstDate,LocalDate lastDate) { }
    public record Report(Config config,DataQuality dataQuality,List<Trade> trades,List<Summary> summary,int skippedGaps,int incompleteSessionExits) { }

    public Report run(CsvCandleReader.Dataset dataset,Config config) {
        List<StrategyCandle> bars=dataset.candles();
        if (bars.isEmpty() || (config.from()!=null && config.from().isAfter(bars.get(bars.size()-1).date()))
                || (config.to()!=null && config.to().isBefore(bars.get(0).date()))) throw new IllegalArgumentException("Requested range does not overlap this dataset");
        var completed=new ArrayList<Trade>();
        Map<String,Position> active=new LinkedHashMap<>(); Map<String,LocalDate> enteredDay=new HashMap<>();
        int skipped=0,incomplete=0;
        for (int i=0;i<bars.size();i++) {
            var bar=bars.get(i); var time=LocalDateTime.of(bar.date(),bar.time());
            // Exit old sessions using their last observed candle, never a price from tomorrow.
            if (i>0 && !bar.date().equals(bars.get(i-1).date())) {
                var previous=bars.get(i-1);
                for (var position:active.values()) { completed.add(finish(position,previous,previous.close(),"INCOMPLETE_SESSION",config)); incomplete++; }
                active.clear();
            }
            for (var iterator=active.entrySet().iterator();iterator.hasNext();) {
                var position=iterator.next().getValue();
                String reason=null;double exit=0;
                if (!bar.time().isBefore(config.squareOff())) { reason="SQUARE_OFF";exit=bar.open(); }
                else if (position.ce ? bar.open()<=position.stop : bar.open()>=position.stop) { reason="STOP_GAP";exit=bar.open(); }
                else if (position.ce ? bar.open()>=position.target : bar.open()<=position.target) { reason="TARGET_GAP";exit=position.target; }
                else if (position.ce ? bar.low()<=position.stop : bar.high()>=position.stop) { reason="STOP";exit=position.stop; }
                else if (position.ce ? bar.high()>=position.target : bar.low()<=position.target) { reason="TARGET";exit=position.target; }
                if (reason!=null) { completed.add(finish(position,bar,exit,reason,config));iterator.remove(); }
            }
            // Generate orders after this completed bar; fill only at the next observed, contiguous open.
            if (i+1>=bars.size()||i+1<indicators.minimumCandles()) continue;
            if ((config.from()!=null&&bar.date().isBefore(config.from()))||(config.to()!=null&&bar.date().isAfter(config.to()))) continue;
            var next=bars.get(i+1);var nextTime=LocalDateTime.of(next.date(),next.time());
            if (!nextTime.equals(time.plusMinutes(5)) || next.time().isBefore(LocalTime.of(9,25))
                    || !next.time().isBefore(LocalTime.of(15,20)) || !next.time().isBefore(config.squareOff())) continue;
            LocalDate earliest=bar.date().minusDays(14);
            List<StrategyCandle> history=bars.subList(Math.max(0,i-249),i+1).stream().filter(candle->!candle.date().isBefore(earliest)).toList();
            if (history.size()<indicators.minimumCandles()) continue;
            IndicatorSnapshot snapshot=indicators.computeSnapshot(history);
            for (String direction:List.of("CE","PE")) {
                if (active.containsKey(direction)||bar.date().equals(enteredDay.get(direction))||!rules.evaluate(config.symbol(),direction,snapshot).passed()) continue;
                boolean ce=direction.equals("CE"); double stop=ce?bar.low():bar.high(); double plannedRisk=Math.abs(bar.close()-stop);
                double target=bar.close()+(ce?1:-1)*config.targetR()*plannedRisk;
                double entry=next.open()+(ce?1:-1)*config.slippagePoints();
                if (plannedRisk<=0 || (ce?(entry<=stop||entry>=target):(entry>=stop||entry<=target))) { skipped++;continue; }
                active.put(direction,new Position(ce,time.plusMinutes(5),nextTime,entry,stop,target));enteredDay.put(direction,bar.date());
            }
        }
        var last=bars.get(bars.size()-1);
        for (var position:active.values()) { completed.add(finish(position,last,last.close(),"END_OF_DATA",config));incomplete++; }
        completed.sort(Comparator.comparing(Trade::exitBarAt).thenComparing(Trade::direction));
        return new Report(config,new DataQuality(dataset.rows(),dataset.outsideSession(),dataset.incompleteGroups(),dataset.missingFiveMinuteSlots(),dataset.normalizedTimestamps(),bars.size(),bars.get(0).date(),bars.get(bars.size()-1).date()),List.copyOf(completed),List.of(summarize("CE",completed),summarize("PE",completed)),skipped,incomplete);
    }
    private Trade finish(Position position,StrategyCandle bar,double rawExit,String reason,Config config) {
        double exit=rawExit+(position.ce?-1:1)*config.slippagePoints();
        double net=(position.ce?1:-1)*(exit-position.entry)-config.costPoints();
        return new Trade(position.ce?"CE":"PE",position.signalAt,position.entryAt,LocalDateTime.of(bar.date(),bar.time()),position.entry,
                position.stop,position.target,exit,reason,net,net/Math.abs(position.entry-position.stop));
    }
    private Summary summarize(String direction,List<Trade> trades) {
        int count=0,wins=0;double net=0,r=0,gains=0,losses=0,peak=0,drawdown=0;
        for (var trade:trades) if (direction.equals(trade.direction())) {
            count++;net+=trade.netPoints();r+=trade.netR();if(trade.netPoints()>0){wins++;gains+=trade.netPoints();}else losses-=trade.netPoints();
            peak=Math.max(peak,net);drawdown=Math.max(drawdown,peak-net);
        }
        return new Summary(direction,count,wins,count==0?0:100d*wins/count,net,count==0?0:r/count,losses==0?null:gains/losses,drawdown);
    }
    private record Position(boolean ce,LocalDateTime signalAt,LocalDateTime entryAt,double entry,double stop,double target) { }
}
