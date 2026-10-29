package org.com.sharekhan.analysis;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.analysis.SwingModels.*;

/** Deterministic research presets v1, not a claimed replication of VCP or a backtested strategy. */
@Component
public class SwingPlanner {
    public StockResult evaluate(ScriptMasterEntity stock, List<HistoricalCandle> source, LocalDate day, LocalDate valid) {
        String symbol = stock.getTradingSymbol(); int code = stock.getScripCode();
        if (valid == null) return result(symbol,code,"UNAVAILABLE","Next entry session crosses an unsupported calendar year or special session.",null,false,false,null);
        var ordered = new TreeMap<LocalDate,HistoricalCandle>();
        for (var c : source) {
            if (c == null || c.date() == null) return result(symbol,code,"UNAVAILABLE","Invalid candle date.",null,false,false,null);
            if (c.date().isAfter(day)) continue; // no future prices or volume enter a historical plan
            var previous = ordered.putIfAbsent(c.date(),c);
            if (!StockAnalysisService.valid(c) || (previous != null && !sameDaily(previous,c)))
                return result(symbol,code,"UNAVAILABLE","Invalid OHLC or conflicting daily timestamps.",c.date(),false,false,null);
        }
        LocalDate lastDate = ordered.isEmpty() ? null : ordered.lastKey();
        if (!day.equals(lastDate)) return result(symbol,code,"UNAVAILABLE",lastDate==null
                ?"No daily bars returned; shared-session access or provider data may be unavailable."
                :"Returned daily history does not yet include the required session.",lastDate,false,false,null);
        var all = new ArrayList<>(ordered.values());
        if (all.size()<220) return result(symbol,code,"UNAVAILABLE","Requires at least 220 completed daily bars.",lastDate,false,false,null);
        var rows = all.subList(Math.max(0,all.size()-260),all.size());
        var bars = rows.stream().map(c->new StockAnalysisService.Bar(c.date(),LocalTime.MIDNIGHT,c.open(),c.high(),c.low(),c.close(),c.volume())).toList();
        var last=bars.getLast(); double s20=StockAnalysisService.sma(bars,20),s50=StockAnalysisService.sma(bars,50),s200=StockAnalysisService.sma(bars,200);
        boolean above50=last.close()>s50,above200=last.close()>s200;
        var recent=bars.subList(bars.size()-20,bars.size());
        if(recent.stream().anyMatch(b->b.volume()==null||b.volume()<=0))return result(symbol,code,"UNAVAILABLE","Last 20 daily volumes must be positive.",lastDate,above50,above200,null);
        double turnover=recent.stream().mapToDouble(b->b.close()*b.volume()).average().orElse(0);
        if(turnover<200_000_000) return result(symbol,code,"FILTERED","Average daily turnover below ₹20 crore.",lastDate,above50,above200,null);
        for(int i=1;i<bars.size();i++)if(Math.abs(bars.get(i).close()/bars.get(i-1).close()-1)>.30)
            return result(symbol,code,"UNAVAILABLE","Large price discontinuity; corporate-action adjustment needs review.",lastDate,above50,above200,null);
        double old50=StockAnalysisService.sma(bars.subList(0,bars.size()-20),50);
        double rsi=StockAnalysisService.rsi(bars,14),atr=StockAnalysisService.atr(bars,14);
        if(!Double.isFinite(atr)||atr<=0)return result(symbol,code,"UNAVAILABLE","ATR unavailable.",lastDate,above50,above200,null);
        if(!(above50&&s20>s50&&s50>s200&&s50>old50))return result(symbol,code,"FILTERED","No rising SMA20 > SMA50 > SMA200 trend alignment.",lastDate,above50,above200,null);
        Double tickValue=stock.getTickSize();
        if(tickValue==null||!Double.isFinite(tickValue)||tickValue<.01||tickValue>1)
            return result(symbol,code,"UNAVAILABLE","Cash tick size unavailable or unsupported.",lastDate,above50,above200,null);
        var prior20=bars.subList(bars.size()-21,bars.size()-1);
        double pivot=prior20.stream().mapToDouble(StockAnalysisService.Bar::high).max().orElseThrow();
        double low5=bars.subList(bars.size()-5,bars.size()).stream().mapToDouble(StockAnalysisService.Bar::low).min().orElseThrow();
        String setup; double trigger;
        if(last.low()<=s20+.5*atr&&last.close()>=s20&&last.close()>last.open()&&rsi>=45&&rsi<=65) {
            setup="TREND_PULLBACK";trigger=last.high()+Math.max(tickValue,.1*atr);
        } else if(last.close()<=pivot&&last.close()>=pivot*.97&&rsi>=50&&rsi<=70) {
            setup="20_DAY_BREAKOUT";trigger=pivot+Math.max(tickValue,.1*atr);
        } else return result(symbol,code,"FILTERED","Trend is healthy, but no defined breakout/pullback setup.",lastDate,above50,above200,null);
        double entry=round(trigger,tickValue,true),max=round(entry+.25*atr,tickValue,true),stop=round(low5-.25*atr,tickValue,false);
        double risk=BigDecimal.valueOf(max).subtract(BigDecimal.valueOf(stop)).doubleValue();
        if(setup.equals("20_DAY_BREAKOUT")&&max>pivot*1.02)
            return result(symbol,code,"FILTERED","Maximum entry exceeds the breakout planner's 2% chase ceiling.",lastDate,above50,above200,null);
        if(stop<=0||risk<atr||risk>2.5*atr||risk/max>.08)
            return result(symbol,code,"FILTERED","Stop distance must be 1–2.5 ATR and at most 8% at maximum entry.",lastDate,above50,above200,null);
        double t1=round(BigDecimal.valueOf(max).add(BigDecimal.valueOf(risk).multiply(BigDecimal.valueOf(2))).doubleValue(),tickValue,true);
        double t2=round(BigDecimal.valueOf(max).add(BigDecimal.valueOf(risk).multiply(BigDecimal.valueOf(3))).doubleValue(),tickValue,true);
        if(setup.equals("TREND_PULLBACK")&&pivot>max&&pivot<t1)
            return result(symbol,code,"FILTERED","Observed 20-day resistance leaves less than 2R of room.",lastDate,above50,above200,null);
        var plan=new Plan(symbol,code,"NC",setup,"Shoonya",day,valid,entry,max,stop,t1,t2,risk,100*risk/max,atr,rsi,turnover,
                "Rising daily trend with "+(setup.equals("TREND_PULLBACK")?"a bullish pullback near SMA20.":"price within 3% below its prior 20-day high.")+" Targets are 2R/3R projections from maximum entry, not forecasts.",
                List.of("Valid for entry only on "+valid+"; expires at 15:15 IST.","Skip if the session opens above maximum entry or the stop is breached before entry.","Review earnings, corporate actions and news before considering entry; these are not screened.","No order is placed. Review holding after 10 completed sessions; gaps can exceed planned risk."));
        return result(symbol,code,"CANDIDATE","Conditional entry; requires portfolio and event-risk review.",lastDate,above50,above200,plan);
    }
    private static boolean sameDaily(HistoricalCandle a,HistoricalCandle b) {
        return a.open()==b.open()&&a.high()==b.high()&&a.low()==b.low()&&a.close()==b.close()&&Objects.equals(a.volume(),b.volume());
    }
    private static StockResult result(String symbol,int code,String status,String detail,LocalDate date,boolean a,boolean b,Plan plan){return new StockResult(symbol,code,status,detail,date,a,b,plan);}
    static double round(double price,double tick,boolean up) {
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(tick),0,up?RoundingMode.CEILING:RoundingMode.FLOOR).multiply(BigDecimal.valueOf(tick)).setScale(2,RoundingMode.UNNECESSARY).doubleValue();
    }
    public Observation observe(Plan plan,List<HistoricalCandle> source,LocalDate through) {
        var days=new TreeMap<LocalDate,HistoricalCandle>();
        for(var c:source)if(c!=null&&c.date()!=null&&!c.date().isAfter(through)&&!c.date().isBefore(plan.validSession())){
            if(!StockAnalysisService.valid(c))return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Invalid outcome prices.");
            var old=days.putIfAbsent(c.date(),c);if(old!=null&&!sameDaily(old,c))return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Conflicting outcome prices.");
        }
        if(through.isBefore(plan.validSession()))return new Observation(plan.symbol(),plan.dataDate(),through,"WATCHLIST","Entry session has not completed.");
        var entry=days.get(plan.validSession());
        if(entry==null)return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Entry-session EOD data missing.");
        var calendar=new SwingSessions();int required=0;
        for(LocalDate date=plan.validSession();!date.isAfter(through)&&required<10;date=date.plusDays(1)) {
            if(calendar.special(date))return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Special-session outcomes are not modelled.");
            if(!calendar.supported(date))return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Outcome calendar needs an update.");
            if(calendar.regular(date)){required++;if(!days.containsKey(date))return new Observation(plan.symbol(),plan.dataDate(),through,"UNAVAILABLE","Required outcome-session data missing.");}
        }
        String state="ENTRY_LEVEL_TOUCHED",detail="Daily range reached the entry. This is not an observed fill.";
        if(entry.open()>plan.maxEntry()){state="GAP_SKIP";detail="Opening price exceeded maximum entry.";}
        else if(entry.open()<=plan.stop()){state="INVALIDATED_BEFORE_ENTRY";detail="Session opened at/below the stop before any valid entry.";}
        else if(entry.high()<plan.entry()){state="EXPIRED";detail="Entry was not reached during its valid session.";}
        else {
            int sessions=0;
            for(var bar:days.values()) {
                if(!calendar.regular(bar.date()))continue;
                sessions++;
                if(bar.low()<=plan.stop()&&bar.high()>=plan.target1()) {state="AMBIGUOUS";detail="Daily OHLC cannot establish the order of stop and target touches.";break;}
                if(bar.date().equals(plan.validSession())&&bar.low()<=plan.stop()&&bar.open()<plan.entry()){state="AMBIGUOUS";detail="Daily OHLC cannot establish whether the stop was breached before entry.";break;}
                if(bar.low()<=plan.stop()){state="STOP_LEVEL_TOUCHED";detail="Observed price reached/breached stop; no execution price or realised P&L inferred.";break;}
                if(bar.high()>=plan.target2()){state="TARGET_2_TOUCHED";detail="Observed daily high reached T2; no fill inferred.";break;}
                if(bar.high()>=plan.target1()){state="TARGET_1_TOUCHED";detail="Observed daily high reached T1; no fill inferred.";}
                if(sessions>=10){state="REVIEW_DUE";detail="Ten completed observations since entry session; review the plan.";break;}
            }
        }
        return new Observation(plan.symbol(),plan.dataDate(),through,state,detail);
    }
}
