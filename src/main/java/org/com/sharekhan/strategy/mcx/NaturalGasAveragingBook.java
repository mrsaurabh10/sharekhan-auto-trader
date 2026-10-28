package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;

/** Rebuilds baskets, costs and daily loss allowance from confirmed persisted fills after restart. */
public final class NaturalGasAveragingBook {
    public static final double CAPITAL=60000, TARGET=1250, DAILY_LOSS=3000, COST_PER_LOT=100;
    public static final int MAX_LOTS=4;
    public static final double OPTION_TICK=.05;
    private NaturalGasAveragingBook() { }
    public static boolean closed(TriggeredTradeSetupEntity t) {
        return t.getStatus()==TriggeredTradeStatus.EXITED_SUCCESS || t.getStatus()==TriggeredTradeStatus.COMPLETED;
    }
    public static boolean uncertain(String s) {return s!=null && (s.contains("UNKNOWN")||s.contains("UNCONFIRMED")||s.contains("RECONCILIATION_REQUIRED"));}
    public record Basket(String id,List<TriggeredTradeSetupEntity> legs,int lots,long quantity,double invested,
                         double costs,double gross,double net,boolean marked,boolean allClosed,boolean addable,
                         double stopFloor,double targetBid,LocalDateTime lastEntry) { }
    public record State(Map<String,Basket> baskets,double closedSpent,double dailyUsed,LocalDateTime lastExit,String problem) {
        public Basket current(String id){return baskets.get(id);}
    }
    public static State inspect(List<TriggeredTradeSetupEntity> input,Long user,Long broker,LocalDateTime now,
                                Map<Integer,Double> bids,double tick) {
        try {
            if(!positive(tick))throw new IllegalArgumentException();
            var groups=new LinkedHashMap<String,List<TriggeredTradeSetupEntity>>();LocalDateTime lastExit=null;
            for(var t:input) {
                if(!Objects.equals(user,t.getAppUserId()) || !Objects.equals(broker,t.getBrokerCredentialsId()))continue;
                if(uncertain(t.getReason()))throw new IllegalArgumentException();
                if(t.getActualEntryPrice()==null && t.getEntryAt()==null && (t.getStatus()==TriggeredTradeStatus.REJECTED || t.getStatus()==TriggeredTradeStatus.CANCELLED))continue;
                if(t.getEntryAt()==null || t.getEntryAt().isAfter(now))throw new IllegalArgumentException();
                if(!t.getEntryAt().toLocalDate().equals(now.toLocalDate())) {if(!closed(t))throw new IllegalArgumentException();continue;}
                if(t.getId()==null || t.getStrategyRunId()==null || t.getQuantity()==null || t.getQuantity()<=0 || !positive(t.getActualEntryPrice())
                        || !"MX".equalsIgnoreCase(t.getExchange()) || !Set.of("CE","PE").contains(t.getOptionType())
                        || (t.getEntryAction()!=null && !"BUY".equalsIgnoreCase(t.getEntryAction())) || t.getLots()==null || t.getLots()!=1
                        || (t.getTradeCost()!=null && (!Double.isFinite(t.getTradeCost()) || t.getTradeCost()<0)))throw new IllegalArgumentException();
                if(closed(t)) {
                    if(t.getExitedAt()==null || t.getExitedAt().isAfter(now) || t.getExitPrice()==null || !Double.isFinite(t.getExitPrice()) || t.getExitPrice()<0)throw new IllegalArgumentException();
                    if(lastExit==null || t.getExitedAt().isAfter(lastExit))lastExit=t.getExitedAt();
                }
                groups.computeIfAbsent(t.getStrategyRunId(),k->new ArrayList<>()).add(t);
            }
            var result=new LinkedHashMap<String,Basket>();double spent=0,used=0;
            for(var entry:groups.entrySet()) {
                var legs=entry.getValue();legs.sort(Comparator.comparing(TriggeredTradeSetupEntity::getEntryAt).thenComparing(TriggeredTradeSetupEntity::getId));
                var first=legs.getFirst();double invested=0,costs=0,gross=0;long quantity=0;boolean marked=true,allClosed=true,addable=true;
                for(var t:legs) {
                    if(!Objects.equals(first.getScripCode(),t.getScripCode()) || !Objects.equals(first.getStrikePrice(),t.getStrikePrice())
                            || !Objects.equals(first.getExpiry(),t.getExpiry()) || !Objects.equals(first.getOptionType(),t.getOptionType()) || !Objects.equals(first.getQuantity(),t.getQuantity()))throw new IllegalArgumentException();
                    invested+=t.getActualEntryPrice()*t.getQuantity();quantity+=t.getQuantity();costs+=cost(t);
                    if(!closed(t)) {allClosed=false;if(t.getStatus()!=TriggeredTradeStatus.EXECUTED)addable=false;}
                    else addable=false;
                    Double mark=closed(t)?t.getExitPrice():bids.get(t.getScripCode());
                    if(mark==null || !Double.isFinite(mark) || mark<0)marked=false;
                    else gross+=(mark-t.getActualEntryPrice())*t.getQuantity();
                }
                if(legs.size()>MAX_LOTS || !Double.isFinite(invested+costs+gross))throw new IllegalArgumentException();
                double consumption=marked?Math.max(0,-gross)+costs:Double.NaN;
                if(allClosed)spent+=consumption;
                if(marked)used+=consumption;else used=Double.NaN;
                result.put(entry.getKey(),new Basket(entry.getKey(),List.copyOf(legs),legs.size(),quantity,invested,costs,gross,gross-costs,marked,allClosed,addable,0,0,legs.getLast().getEntryAt()));
            }
            // Prefix floors never move down as lots are added, and survive a process restart.
            for(var entry:new ArrayList<>(result.entrySet())) {
                var b=entry.getValue();if(b.allClosed())continue;
                double prefixCost=0,prefixFees=0,floor=tick,realized=0,openCost=0;long prefixQty=0,openQty=0;
                for(var t:b.legs()) {
                    prefixCost+=t.getActualEntryPrice()*t.getQuantity();prefixFees+=cost(t);prefixQty+=t.getQuantity();
                    floor=Math.max(floor,ceil((prefixCost-(DAILY_LOSS-spent-prefixFees))/prefixQty,tick));
                    if(closed(t))realized+=(t.getExitPrice()-t.getActualEntryPrice())*t.getQuantity();
                    else {openQty+=t.getQuantity();openCost+=t.getActualEntryPrice()*t.getQuantity();}
                }
                if(openQty==0)throw new IllegalArgumentException();
                floor=Math.max(floor,ceil((openCost-realized-(DAILY_LOSS-spent-b.costs()))/openQty,tick));
                double target=ceil((openCost-realized+b.costs()+TARGET)/openQty,tick);
                result.put(entry.getKey(),new Basket(b.id(),b.legs(),b.lots(),b.quantity(),b.invested(),b.costs(),b.gross(),b.net(),b.marked(),false,b.addable(),floor,target,b.lastEntry()));
            }
            return new State(Map.copyOf(result),spent,used,lastExit,null);
        } catch(RuntimeException invalid) {return new State(Map.of(),0,Double.NaN,null,"Basket fill/contract/clock data needs reconciliation; no additions allowed");}
    }
    public static String admit(State state,String runId,double ask,double bid,int lot,double tick) {
        if(state.problem()!=null)return state.problem();
        if(!positive(ask)||!positive(bid)||bid>ask||lot<=0||!positive(tick)||!Double.isFinite(state.dailyUsed()))return "Fresh executable option marks required before entry";
        if(state.baskets().values().stream().anyMatch(b->!b.allClosed()&&!b.id().equals(runId)))return "Another averaging basket is still open or closing";
        var b=state.current(runId);int lots=b==null?0:b.lots();
        if(lots>=MAX_LOTS)return "Maximum four filled lots reached";
        if(b!=null && (!b.addable()||!b.marked()))return "Basket exit or entry reconciliation required before adding";
        if(b!=null && (bid<=b.stopFloor()+1e-8 || b.net()>=TARGET))return "Basket exit condition already reached; no additions";
        double invested=b==null?0:b.invested(),costs=b==null?0:b.costs();
        if(invested+ask*lot+costs+COST_PER_LOT+state.closedSpent()>CAPITAL+1e-8)return "ATM lot exceeds remaining ₹60,000 capital including cost reserves";
        if(state.dailyUsed()+(ask-bid)*lot+COST_PER_LOT>=DAILY_LOSS-1e-8)return "Proposed fill exhausts the remaining ₹3,000 daily allowance";
        double floor=ceil((invested+ask*lot-(DAILY_LOSS-state.closedSpent()-costs-COST_PER_LOT))/((b==null?0:b.quantity())+lot),tick);
        if(b!=null)floor=Math.max(floor,b.stopFloor());
        if(floor>=bid-1e-8)return "Projected basket stop leaves no room below the executable bid";
        return null;
    }
    private static double cost(TriggeredTradeSetupEntity t){return Math.max(COST_PER_LOT,Objects.requireNonNullElse(t.getTradeCost(),0d));}
    static double ceil(double x,double tick){return java.math.BigDecimal.valueOf(x).divide(java.math.BigDecimal.valueOf(tick),0,java.math.RoundingMode.CEILING).multiply(java.math.BigDecimal.valueOf(tick)).doubleValue();}
    private static boolean positive(Double x){return x!=null&&Double.isFinite(x)&&x>0;}
}
