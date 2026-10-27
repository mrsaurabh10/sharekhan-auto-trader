package org.com.sharekhan.strategy.mcx;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.exception.EntryNotReadyException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Restart-safe admission budget from persisted requests/fills, shared across NGAS subscriptions. */
public record NaturalGasScalpDay(BigDecimal remaining,int attempts,LocalDateTime lastActivity,String problem) {
    public static NaturalGasScalpDay inspect(StrategyApplyRequest request,List<TriggerTradeRequestEntity> requests,
                                            List<TriggeredTradeSetupEntity> trades,LocalDateTime now) {
        BigDecimal limit=NaturalGasScalpPolicy.DAILY_LIMIT.min(request.getRiskSettings().scale(request.getRiskSettings().getMaxLoss(),request.getLots()));
        double spent=0;var attempts=new HashSet<String>();LocalDateTime activity=null;
        for(var r:requests) {
            if(!owned(request,r.getAppUserId(),r.getBrokerCredentialsId()))continue;
            if(r.getStatus()==TriggeredTradeStatus.REJECTED && EntryNotReadyException.NO_ORDER_REASON.equals(r.getReason()))continue;
            if(r.getCreatedAt()==null || r.getCreatedAt().isAfter(now))return unavailable(limit,"Request clock unavailable; reconcile before re-entry");
            if(!r.getCreatedAt().toLocalDate().equals(now.toLocalDate()))continue;
            attempts.add("request:"+Objects.requireNonNull(r.getId(),"Persisted request ID required"));
            if(activity==null || activity.isBefore(r.getCreatedAt()))activity=r.getCreatedAt();
        }
        for(var t:trades) {
            if(!owned(request,t.getAppUserId(),t.getBrokerCredentialsId()))continue;
            var entry=t.getEntryAt();
            boolean filled=t.getOrderId()!=null || entry!=null || t.getActualEntryPrice()!=null;
            if(!filled)continue;
            if(entry==null || entry.isAfter(now))return unavailable(limit,"Filled trade clock unavailable; reconcile before re-entry");
            if(!entry.toLocalDate().equals(now.toLocalDate()))continue;
            attempts.add(t.getTriggerRequestId()!=null?"request:"+t.getTriggerRequestId():"trade:"+Objects.requireNonNull(t.getId()));
            if(t.getStatus()==null || !Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED).contains(t.getStatus())
                    || t.getExitedAt()==null || t.getExitedAt().isAfter(now))return unavailable(limit,"Previous fill must have a confirmed exit before re-entry");
            if(t.getQuantity()==null || t.getQuantity()<=0 || !positive(t.getActualEntryPrice()) || t.getExitPrice()==null
                    || !Double.isFinite(t.getExitPrice()) || t.getExitPrice()<0)return unavailable(limit,"Closed fill prices/quantity unavailable; daily budget cannot be verified");
            if(t.getTradeCost()!=null && (!Double.isFinite(t.getTradeCost()) || t.getTradeCost()<0))return unavailable(limit,"Invalid recorded costs; reconcile daily budget");
            // Profits never replenish this conservative loss allowance.
            spent+=lossUsed(t,t.getExitPrice());
            if(activity==null || activity.isBefore(t.getExitedAt()))activity=t.getExitedAt();
        }
        var remaining=limit.subtract(BigDecimal.valueOf(spent)).max(BigDecimal.ZERO);
        String problem=null;
        if(attempts.size()>=NaturalGasScalpPolicy.MAX_ATTEMPTS)problem="Scalp daily attempt cap reached ("+attempts.size()+"/"+NaturalGasScalpPolicy.MAX_ATTEMPTS+")";
        else if(remaining.compareTo(BigDecimal.valueOf(NaturalGasScalpPolicy.COST_RESERVE))<=0)problem="Scalp daily loss allowance exhausted; no more entries today";
        else if(activity!=null && now.isBefore(activity.plusMinutes(NaturalGasScalpPolicy.COOLDOWN_MINUTES)))problem="Scalp cooldown until "+activity.plusMinutes(NaturalGasScalpPolicy.COOLDOWN_MINUTES);
        return new NaturalGasScalpDay(remaining,attempts.size(),activity,problem);
    }
    private static NaturalGasScalpDay unavailable(BigDecimal limit,String reason){return new NaturalGasScalpDay(BigDecimal.ZERO,0,null,reason);}
    private static boolean positive(Double p){return p!=null && Double.isFinite(p) && p>0;}
    public static double lossUsed(TriggeredTradeSetupEntity trade,double exitMark) {
        if(!positive(trade.getActualEntryPrice())||trade.getQuantity()==null||trade.getQuantity()<=0||!Double.isFinite(exitMark)||exitMark<0
                ||(trade.getTradeCost()!=null&&(!Double.isFinite(trade.getTradeCost())||trade.getTradeCost()<0)))throw new IllegalArgumentException("Fill or cost data unavailable for scalp risk");
        double gross=(exitMark-trade.getActualEntryPrice())*trade.getQuantity();
        double used=Math.max(0,-gross)+Math.max(NaturalGasScalpPolicy.COST_RESERVE,Objects.requireNonNullElse(trade.getTradeCost(),0d));
        if(!Double.isFinite(used))throw new IllegalArgumentException("Invalid scalp P&L");return used;
    }
    private static boolean owned(StrategyApplyRequest r,Long user,Long broker){return Objects.equals(r.getUserId(),user)&&Objects.equals(r.getBrokerCredentialsId(),broker);}
    public String context(){return "; scalpDailyRemaining="+remaining+"; attempts="+attempts+"/"+NaturalGasScalpPolicy.MAX_ATTEMPTS+"; paperCostReservePerFill="+NaturalGasScalpPolicy.COST_RESERVE;}
}
