package org.com.sharekhan.analysis;

import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import java.time.LocalDate;
import java.util.*;

/** Confirmed 1.5-ATR reversal swings, top-three starts, as-of-only 120-bar pattern window. */
final class VcpPattern {
    record Swing(int index,double value) { }
    record Contraction(LocalDate highDate,LocalDate lowDate,double high,double low,double depthPercent,int duration,int highIndex,int lowIndex) { }
    record Result(boolean valid,List<Contraction> contractions,Double pivot,List<String> issues) { }
    static Result detect(List<HistoricalCandle> history) {
        var bars=history.subList(Math.max(0,history.size()-120),history.size());int n=bars.size();
        if(n<50)return new Result(false,List.of(),null,List.of("Requires at least 50 pattern bars."));
        double threshold=1.5*atr(bars,14);var highs=new ArrayList<Swing>();var lows=new ArrayList<Swing>();
        int extreme=0;for(int i=1;i<14;i++)if(bars.get(i).high()>bars.get(extreme).high())extreme=i;
        boolean seekingHigh=true;double value=bars.get(extreme).high();
        for(int i=extreme+1;i<n;i++) {
            var b=bars.get(i);
            if(seekingHigh){if(b.high()>value){value=b.high();extreme=i;}else if(value-b.low()>=threshold){highs.add(new Swing(extreme,value));seekingHigh=false;extreme=i;value=b.low();}}
            else {if(b.low()<value){value=b.low();extreme=i;}else if(b.high()-value>=threshold){lows.add(new Swing(extreme,value));seekingHigh=true;extreme=i;value=b.high();}}
        }
        Result best=new Result(false,List.of(),null,List.of("No confirmed contraction pair."));
        var starts=highs.stream().sorted(Comparator.comparingDouble(Swing::value).reversed()).limit(3).toList();
        for(var start:starts) {
            var sequence=new ArrayList<Contraction>();var h=start;
            for(int k=0;k<4;k++) {
                Swing current=h;var low=lows.stream().filter(s->s.index()>current.index()).findFirst().orElse(null);
                if(low==null||low.index()-h.index()<5||!sequence.isEmpty()&&Math.abs(h.value()/start.value()-1)>.05)break;
                double depth=100*(h.value()-low.value())/h.value();
                if(depth<=0)break;
                sequence.add(new Contraction(bars.get(h.index()).date(),bars.get(low.index()).date(),h.value(),low.value(),depth,low.index()-h.index(),h.index(),low.index()));
                int after=low.index();h=highs.stream().filter(s->s.index()>after).findFirst().orElse(null);if(h==null)break;
            }
            var candidate=validate(sequence,n);
            if(compare(candidate,best)>0)best=candidate;
        }
        return best;
    }
    private static int compare(Result a,Result b){int x=Boolean.compare(a.valid(),b.valid());return x!=0?x:Integer.compare(a.contractions().size(),b.contractions().size());}
    static Result validate(List<Contraction> sequence,int bars) {
        var issues=new ArrayList<String>();
        if(sequence.size()<2)issues.add("At least two confirmed contractions required.");
        if(!sequence.isEmpty()) {
            if(sequence.getFirst().depthPercent()<10||sequence.getFirst().depthPercent()>35)issues.add("First contraction must be 10–35%.");
            for(int i=1;i<sequence.size();i++)if(sequence.get(i).depthPercent()/sequence.get(i-1).depthPercent()>.70)issues.add("Successive depths must shrink to at most 70% of the previous depth.");
            if(sequence.getLast().lowIndex()-sequence.getFirst().highIndex()<15)issues.add("Pattern must span at least 15 sessions.");
            if(sequence.getLast().depthPercent()>10)issues.add("Final contraction exceeds 10%.");
            if(bars-1-sequence.getLast().lowIndex()>15)issues.add("Last contraction low is older than 15 sessions.");
        }
        return new Result(issues.isEmpty(),List.copyOf(sequence),sequence.isEmpty()?null:sequence.getLast().high(),List.copyOf(issues));
    }
    static double atr(List<HistoricalCandle> bars,int period) {
        if(bars.size()<period+1)return Double.NaN;double a=0;
        for(int i=1;i<bars.size();i++){var b=bars.get(i);double previous=bars.get(i-1).close();double tr=Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-previous),Math.abs(b.low()-previous)));if(i<=period){a+=tr;if(i==period)a/=period;}else a=(a*(period-1)+tr)/period;}
        return a;
    }
    private VcpPattern() { }
}
