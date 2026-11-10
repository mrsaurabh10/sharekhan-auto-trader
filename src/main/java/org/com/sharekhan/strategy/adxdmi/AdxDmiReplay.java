package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;

/** Offline underlying proxy. Three equal units, original SL, independently reserved 1R/2R/3R brackets. */
public final class AdxDmiReplay {
    public record Config(String symbol,LocalDate from,LocalDate to,boolean morningOnly,boolean rollingHistory) {
        public Config(LocalDate from,LocalDate to,boolean morningOnly){this("NIFTY",from,to,morningOnly,true);}
        public Config(String symbol,LocalDate from,LocalDate to,boolean morningOnly){this(symbol,from,to,morningOnly,true);}
        public Config {symbol=AdxDmiRules.symbol(symbol);if(from==null || to==null || from.isAfter(to))throw new IllegalArgumentException("Valid from/to required");}
    }
    public record Exit(int cycle,String side,LocalDateTime signalAt,LocalDateTime entryBar,LocalDateTime exitBar,
                       int leg,double entry,double stop,double target,double exit,String reason,double points,double r) { }
    public record Decision(LocalDateTime bar,AdxDmiRules.Reading dmi,String state,String signalSide,double trigger,double stop) { }
    public record Summary(int cycles,int winningCycles,int closedLegs,double points,double averagePerInitialUnit,
                          Double legProfitFactor,double closedCycleDrawdown,double markedEquityDrawdown,int openLegs) { }
    public record Report(String unit,Config config,Summary summary,List<Exit> exits,List<Decision> decisions) { }
    public Report run(List<StrategyCandle> bars,Config config){return run(bars,config,Map.of());}
    /** Optional one-minute subdivisions change fills only; signals still use completed five-minute bars. */
    public Report run(List<StrategyCandle> bars,Config config,Map<LocalDateTime,List<StrategyCandle>> subdivisions){
        var dmi=new AdxDmiRules.Dmi();var state=new State();var decisions=new ArrayList<Decision>();
        for(int index=0;index<bars.size();index++){
            var bar=bars.get(index);var reading=dmi.add(bar);var at=AdxDmiRules.start(bar);
            if(config.rollingHistory()){
                var rolling=new AdxDmiRules.Dmi();
                for(int prior=Math.max(0,index-249);prior<=index;prior++)reading=rolling.add(bars.get(prior));
            }
            if(bar.date().isAfter(config.to()))break;
            if(bar.date().isBefore(config.from()))continue;
            if(state.position!=null && !state.position.entryAt.toLocalDate().equals(bar.date()))throw new IllegalArgumentException("Missing square-off candle; cannot replay an intraday position overnight");
            state.fillThisBar=false;state.bar=at;
            if(state.pending!=null && !state.pending.end().toLocalDate().equals(bar.date()))state.pending=null;
            List<StrategyCandle> ticks=subdivisions.getOrDefault(at,List.of(bar));
            if(ticks.isEmpty())throw new IllegalArgumentException("Missing execution candles at "+at);
            for(var tick:ticks)state.walk(tick);
            var close=at.plusMinutes(5);
            if(!close.toLocalTime().isBefore(AdxDmiRules.SQUARE_OFF)){
                state.pending=null;
                if(state.position!=null)state.closeAll(bar.close(),"SESSION_CLOSE");
            }else if(!AdxDmiRules.entryWindow(close,config.morningOnly()))state.pending=null;
            else if(state.position==null && !state.fillThisBar){
                // One-bar validity: the old order was executable throughout this candle, then expires.
                state.pending=config.rollingHistory() && index<199?null:AdxDmiRules.signal(bar,reading,config.morningOnly()).orElse(null);
            }
            var pending=state.pending;
            decisions.add(new Decision(at,reading,state.position!=null?"OPEN":pending!=null?"PENDING":"FLAT",
                    pending==null?null:pending.side(),pending==null?Double.NaN:pending.trigger(),pending==null?Double.NaN:pending.stop()));
            state.mark(bar.close());
        }
        var outcomes=new LinkedHashMap<Integer,Double>();double gains=0,losses=0;
        for(var e:state.exits){outcomes.merge(e.cycle(),e.points(),Double::sum);if(e.points()>0)gains+=e.points();else losses-=e.points();}
        int wins=0;double net=0,peak=0,dd=0;
        for(var value:outcomes.values()){if(value>1e-8)wins++;net+=value;peak=Math.max(peak,net);dd=Math.max(dd,peak-net);}
        var summary=new Summary(state.cycles,wins,state.exits.size(),net,net/3,losses==0?null:gains/losses,dd,state.drawdown,state.position==null?0:state.position.remaining());
        return new Report("THREE_UNDERLYING_PROXY_UNITS; NOT OPTION INR",config,summary,List.copyOf(state.exits),List.copyOf(decisions));
    }
    private static final class Position {
        final AdxDmiRules.Signal signal;final double entry,risk;final LocalDateTime entryAt;final int cycle;
        final boolean[] done=new boolean[3];
        Position(AdxDmiRules.Signal signal,double entry,LocalDateTime at,int cycle){this.signal=signal;this.entry=entry;this.entryAt=at;this.cycle=cycle;this.risk=signal.risk(entry);}
        int remaining(){int count=0;for(boolean value:done)if(!value)count++;return count;}
    }
    private static final class State {
        AdxDmiRules.Signal pending;Position position;LocalDateTime bar;int cycles;boolean fillThisBar;
        double realized,peak,drawdown;final List<Exit> exits=new ArrayList<>();
        void mark(double price){double equity=realized+(position==null?0:position.signal.direction()*(price-position.entry)*position.remaining());peak=Math.max(peak,equity);drawdown=Math.max(drawdown,peak-equity);}
        void enter(double price){position=new Position(pending,price,bar,++cycles);pending=null;fillThisBar=true;mark(price);}
        void exit(int leg,double price,String reason){
            var p=position;double points=p.signal.direction()*(price-p.entry);p.done[leg]=true;realized+=points;fillThisBar=true;
            exits.add(new Exit(p.cycle,p.signal.side(),p.signal.end(),p.entryAt,bar,leg+1,p.entry,p.signal.stop(),p.signal.target(p.entry,leg+1),price,reason,points,points/p.risk));
            if(p.remaining()==0)position=null;mark(price);
        }
        void closeAll(double price,String reason){for(int leg=0;leg<3 && position!=null;leg++)if(!position.done[leg])exit(leg,price,reason);}
        void atOpen(double price){
            if(pending!=null && pending.crossed(price))enter(price);
            if(position==null)return;
            var p=position;
            if(p.signal.direction()*(price-p.signal.stop())<=1e-8){closeAll(price,"STOP");return;}
            for(int leg=0;leg<3 && position!=null;leg++)if(!p.done[leg] && p.signal.direction()*(price-p.signal.target(p.entry,leg+1))>=-1e-8)exit(leg,price,"T"+(leg+1));
        }
        void segment(double from,double to){
            if(pending!=null && pending.crossed(to)){
                // A stop entry is filled on the first crossing, never at the segment's endpoint.
                double trigger=pending.trigger();enter(trigger);from=trigger;
            }
            if(position==null)return;
            var p=position;int direction=p.signal.direction();
            if(direction*(to-from)<0 && direction*(to-p.signal.stop())<=1e-8){mark(p.signal.stop());closeAll(p.signal.stop(),"STOP");}
            else if(direction*(to-from)>0){
                for(int leg=0;leg<3 && position!=null;leg++)if(!p.done[leg]){
                    double target=p.signal.target(p.entry,leg+1);
                    if(direction*(to-target)>=-1e-8){mark(target);exit(leg,target,"T"+(leg+1));}
                }
            }
            mark(to);
        }
        void walk(StrategyCandle b){
            atOpen(b.open());mark(b.open());
            boolean highFirst=b.high()-b.open()<b.open()-b.low();
            double first=highFirst?b.high():b.low(),second=highFirst?b.low():b.high();
            segment(b.open(),first);segment(first,second);segment(second,b.close());
        }
    }
}
