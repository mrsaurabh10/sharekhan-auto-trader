package org.com.sharekhan.strategy.nifty;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.strategy.StrategyCandle;
import org.springframework.stereotype.Service;

/** Original spot risk gates eligibility; confirmed pivots can only tighten on the profit side. */
@Service @RequiredArgsConstructor
public class NiftyThreeCandleTrailing {
    private final NiftyThreeCandleMarketData data;
    private final ScriptMasterRepository scripts;
    public record Candidate(double stop, LocalDateTime pivot, LocalDateTime confirmed) { }
    public static boolean eligible(TriggeredTradeSetupEntity t) {
        return t!=null && NiftyThreeCandleRules.owns(t.getSource()) && t.getEntryAt()!=null
                && t.getEntryPrice()!=null && t.getStopLoss()!=null
                && Double.isFinite(t.getEntryPrice()) && Double.isFinite(t.getStopLoss())
                && Set.of("CE","PE").contains(Objects.toString(t.getOptionType(),""))
                && BigDecimal.valueOf(t.getEntryPrice()).subtract(BigDecimal.valueOf(t.getStopLoss())).abs()
                    .compareTo(BigDecimal.valueOf(50))>0;
    }
    public Candidate candidate(TriggeredTradeSetupEntity t,LocalDateTime now) {
        if(!eligible(t))return null;
        if(t.getSpotScripCode()==null)return null;
        var spot=scripts.findByScripCode(t.getSpotScripCode());
        return spot==null?null:candidate(t,data.candles(spot,now),now);
    }
    static Candidate candidate(TriggeredTradeSetupEntity t,List<StrategyCandle> input,LocalDateTime now) {
        if(!eligible(t))return null;
        var sorted=new TreeMap<LocalDateTime,StrategyCandle>();
        for(var b:input){
            if(b==null||b.date()==null||b.time()==null)continue;
            var at=LocalDateTime.of(b.date(),b.time());
            if(!b.date().equals(t.getEntryAt().toLocalDate()) || b.time().isBefore(LocalTime.of(9,15))
                    || !b.time().isBefore(LocalTime.of(15,30)) || b.time().getMinute()%5!=0
                    || b.time().getSecond()!=0 || b.time().getNano()!=0 || at.plusMinutes(5).isAfter(now))continue;
            if(!Double.isFinite(b.open())||!Double.isFinite(b.close())||!Double.isFinite(b.high())||!Double.isFinite(b.low())
                    ||b.low()<=0||b.low()>Math.min(b.open(),b.close())||b.high()<Math.max(b.open(),b.close()))return null;
            var previous=sorted.putIfAbsent(at,b);
            if(previous!=null&&!previous.equals(b))return null;
        }
        boolean ce="CE".equals(t.getOptionType());
        double active=activeStop(t);Candidate best=null;
        var bars=new ArrayList<>(sorted.values());
        for(int i=1;i+1<bars.size();i++){
            var l=bars.get(i-1);var p=bars.get(i);var r=bars.get(i+1);
            var at=LocalDateTime.of(p.date(),p.time());
            if(at.isBefore(t.getEntryAt())||!l.time().plusMinutes(5).equals(p.time())||!p.time().plusMinutes(5).equals(r.time()))continue;
            boolean pivot=ce?p.low()<l.low()&&p.low()<r.low():p.high()>l.high()&&p.high()>r.high();
            if(!pivot)continue;
            double stop=Math.round((ce?p.low()-2:p.high()+2)*100)/100d;
            if(ce?stop<=t.getEntryPrice()||stop<=active:stop>=t.getEntryPrice()||stop>=active)continue;
            active=stop;best=new Candidate(stop,at,at.plusMinutes(10));
        }
        return best;
    }
    static double activeStop(TriggeredTradeSetupEntity t) {
        double original=t.getStopLoss();var trail=t.getTrailingSl();
        if(!eligible(t)||trail==null||!Double.isFinite(trail))return original;
        if("CE".equals(t.getOptionType()))return trail>t.getEntryPrice()?Math.max(original,trail):original;
        return trail<t.getEntryPrice()?Math.min(original,trail):original;
    }
}
