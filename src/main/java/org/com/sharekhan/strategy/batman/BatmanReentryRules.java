package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import java.time.*;
import java.util.*;

/** Eligibility uses confirmed flat state and actual close date, never merely a stop/target signal. */
public final class BatmanReentryRules {
    private static final Set<TriggeredTradeStatus> FLAT=EnumSet.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED,TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED,TriggeredTradeStatus.FAILED);
    public static boolean confirmedFlat(StrategyRun run,List<TriggeredTradeSetupEntity> trades,List<TriggerTradeRequestEntity> requests){
        if(run==null||!"CLOSED".equals(run.getStatus())||run.getClosedAt()==null)return false;
        for(var t:trades)if(!Objects.equals(t.getStrategyRunId(),run.getId())||!Objects.equals(t.getAppUserId(),run.getAppUserId())||!Objects.equals(t.getBrokerCredentialsId(),run.getBrokerCredentialsId())||!flat(t.getStatus(),t.getReason()))return false;
        for(var r:requests){
            if(!Objects.equals(r.getAppUserId(),run.getAppUserId())||!Objects.equals(r.getBrokerCredentialsId(),run.getBrokerCredentialsId())||!Objects.equals(r.getStrategyRunId(),run.getId())||uncertain(r.getReason()))return false;
            if(r.getStatus()==TriggeredTradeStatus.EXECUTED){if(trades.stream().noneMatch(t->Objects.equals(r.getId(),t.getTriggerRequestId())))return false;}
            else if(!flat(r.getStatus(),r.getReason()))return false;
        }
        return true;
    }
    public static String cooldown(BatmanBasket prior,StrategyRun run,LocalDate day){
        if(!prior.getClaimedAt().toLocalDate().isBefore(day))return "A Batman basket was already attempted today; next-day entries only";
        if(run.getClosedAt()==null||!run.getClosedAt().toLocalDate().isBefore(day))return "Prior basket has not completed its next-trading-day cooldown";
        if(Set.of("STRATEGY_PROFIT_TARGET","STRATEGY_MAX_LOSS").contains(Objects.toString(run.getExitReason(),"")))return null;
        if(!prior.getExpiry().isBefore(day))return "Prior basket did not finish at SL or target; automatic re-entry is blocked pending review or expiry";
        return null;
    }
    private static boolean flat(TriggeredTradeStatus status,String reason){return status!=null&&FLAT.contains(status)&&!uncertain(reason);}
    private static boolean uncertain(String reason){return reason!=null&&(reason.contains("UNKNOWN")||reason.contains("UNCONFIRMED")||reason.contains("RECONCILIATION_REQUIRED"));}
    private BatmanReentryRules(){}
}
