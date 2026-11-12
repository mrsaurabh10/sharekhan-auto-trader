package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.util.TradeSide;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Pure strategy definition; market-data and execution brokers are separate concerns. */
public final class BatmanRules {
    public static final String TEMPLATE="BATMAN_MONTHLY", PREFIX="batman:";
    public static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    public record Option(String side,double strike,double delta,double bid,double ask,long bidQty,long askQty,String securityId) { }
    public record Leg(Option option,String action,int ratio) { }
    public static boolean owns(String source){return source!=null&&source.startsWith(PREFIX);}
    public static void validate(StrategyApplyRequest r){
        if(r==null||r.getSymbol()==null||!r.getSymbol().trim().toUpperCase(Locale.ROOT).matches("[A-Z0-9_ &-]{1,32}"))throw new IllegalArgumentException("Select one underlying symbol");
        r.setSymbol(r.getSymbol().trim().toUpperCase(Locale.ROOT));
        if(r.getLots()==null)r.setLots(1);
        if(r.getLots()<1||r.getLots()>100)throw new IllegalArgumentException("Batman base lots must be from 1 to 100");
        if(org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(r.getRiskSettings(),r.getLots())!=null||r.getProfitTargetPerLot()!=null)throw new IllegalArgumentException("Batman TP and SL are computed from actual center credit; custom risk overrides are not supported");
        r.setRiskSettings(null);r.setIntraday(false);r.setSource("strategy:"+TEMPLATE);
    }
    public static boolean entryWindow(LocalDateTime now){return !now.toLocalTime().isBefore(LocalTime.of(9,20))&&now.toLocalTime().isBefore(LocalTime.of(9,21));}
    public static Optional<LocalDate> expiry(Collection<LocalDate> listed,LocalDate day,Predicate<LocalDate> trading){
        // Weekly and monthly series are both eligible; never invent an expiry from a weekday.
        if(!trading.test(day))return Optional.empty();
        return listed.stream().filter(e->{long d=java.time.temporal.ChronoUnit.DAYS.between(day,e);return d>=40&&d<=50;})
                .min(Comparator.<LocalDate>comparingLong(e->Math.abs(java.time.temporal.ChronoUnit.DAYS.between(day,e)-45)).thenComparing(Comparator.naturalOrder()));
    }

    public static List<Leg> select(List<Option> chain,double spot){
        if(!Double.isFinite(spot)||spot<=0)throw new IllegalArgumentException("Fresh option-chain spot is required");
        List<Leg> longs=new ArrayList<>(),shorts=new ArrayList<>();
        for(String side:List.of("CE","PE")){
            var options=chain.stream().filter(o->side.equals(o.side())&&valid(o)&&("CE".equals(side)?o.strike()>spot:o.strike()<spot)).toList();
            var buy=nearest(options,.20);
            var sell=nearest(options.stream().filter(o->"CE".equals(side)?o.strike()>buy.strike():o.strike()<buy.strike()).toList(),.16);
            if(Math.abs(buy.delta())<=Math.abs(sell.delta()))throw new IllegalArgumentException("Delta order does not match the 1:2 ratio spread");
            longs.add(new Leg(buy,"BUY",1));shorts.add(new Leg(sell,"SELL",2));
        }
        longs.addAll(shorts);return List.copyOf(longs); // Long protection fills before any short entry.
    }
    private static Option nearest(List<Option> list,double desired){
        var o=list.stream().min(Comparator.<Option>comparingDouble(x->Math.abs(Math.abs(x.delta())-desired)).thenComparingDouble(Option::strike)).orElseThrow(()->new IllegalArgumentException("Required delta strike is unavailable"));
        if(Math.abs(Math.abs(o.delta())-desired)>.015)throw new IllegalArgumentException("No listed strike within 1.5 delta points of "+desired);
        return o;
    }
    private static boolean valid(Option o){return Double.isFinite(o.strike())&&o.strike()>0&&Double.isFinite(o.delta())&&Math.abs(o.delta())>0&&Math.abs(o.delta())<1
            &&("CE".equals(o.side())?o.delta()>0:o.delta()<0)&&Double.isFinite(o.bid())&&Double.isFinite(o.ask())&&o.bid()>0&&o.ask()>=o.bid()
            &&o.bidQty()>0&&o.askQty()>0&&o.securityId()!=null&&o.securityId().matches("[0-9]+")&&(o.ask()-o.bid())/o.ask()<=.05;}
    public static BigDecimal credit(List<TriggeredTradeSetupEntity> trades){
        if(trades.size()!=4)throw new IllegalArgumentException("Four confirmed legs are required");
        BigDecimal credit=BigDecimal.ZERO;
        for(var t:trades){if(t.getActualEntryPrice()==null||!Double.isFinite(t.getActualEntryPrice())||t.getActualEntryPrice()<=0||t.getQuantity()==null||t.getQuantity()<=0)throw new IllegalArgumentException("Confirmed fills are required");
            credit=credit.add(BigDecimal.valueOf(t.getActualEntryPrice()).multiply(BigDecimal.valueOf(t.getQuantity())).multiply(BigDecimal.valueOf(TradeSide.isShort(t)?1:-1)));}
        if(credit.signum()<=0)throw new IllegalArgumentException("Batman requires a positive center credit after fills");
        return credit.setScale(2,RoundingMode.HALF_UP);
    }
    private BatmanRules(){}
}
