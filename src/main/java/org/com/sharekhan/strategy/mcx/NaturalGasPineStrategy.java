package org.com.sharekhan.strategy.mcx;

import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.springframework.stereotype.Component;

/** Futures decisions and absolute futures brackets; the traded leg is always a bought option. */
@Component @RequiredArgsConstructor
public class NaturalGasPineStrategy implements StrategyEvaluator {
    public static final String TEMPLATE="MCX_NATURALGAS_PINE_TREND", SOURCE="strategy:"+TEMPLATE;
    private static final StrategyMetadata META=new StrategyMetadata(TEMPLATE,"NG EMA / VWAP · Futures SL & target",
            "Buy ATM CE/PE from completed futures 5m EMA9/21 crossover, session VWAP, confirmed hourly EMA50 and ATR14 > SMA20 × 1.1. Futures stop 1.5 ATR, target 2R; option premium does not define these levels. Default entries 18:30–23:15, exit 23:25 IST; EIA blackout; max three entries per account/day. Simulator or Sharekhan; rupee loss cap required. Optional pullback mode is configured separately.","AUTO");
    private final ScriptMasterRepository scripts;
    private final BrokerCredentialsRepository brokers;
    private final TriggerTradeRequestRepository requests;
    private final TriggeredTradeSetupRepository setups;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    private final TradeExecutionService execution;
    private final StrategySupport support;
    private final NaturalGasDataPolicy dataPolicy;
    private final NaturalGasPinePolicy policy;
    @Override public StrategyMetadata metadata(){return META;}
    public static boolean owns(String source){return SOURCE.equalsIgnoreCase(source);}
    public static boolean isTemplate(String template){return TEMPLATE.equalsIgnoreCase(template);}
    public static void validate(StrategyApplyRequest r) {
        if(r.getSymbol()==null || r.getSymbol().isBlank())r.setSymbol("NATURALGAS");
        r.setSymbol(r.getSymbol().trim().toUpperCase(Locale.ROOT));
        if(!Set.of("NATURALGAS","NATGASMINI").contains(r.getSymbol()))throw new IllegalArgumentException("Select NATURALGAS or NATGASMINI");
        r.setIntraday(true);
        if(r.getLots()==null)r.setLots(1);
        if(r.getLots()<1 || r.getLots()>10)throw new IllegalArgumentException("NG Pine supports 1–10 lots");
        r.setRiskSettings(StrategyRiskSettings.validate(r.getRiskSettings(),r.getLots()));
        if(r.getRiskSettings()==null || r.getRiskSettings().getMaxLoss()==null)throw new IllegalArgumentException("Set a rupee maximum loss for the NG Pine run");
        if(r.getRiskSettings().getProfitTarget()!=null || r.getRiskSettings().getTrailActivation()!=null || r.getRiskSettings().getSlStepProfit()!=null)
            throw new IllegalArgumentException("NG Pine uses futures targets/stops; leave premium profit targets and trailing controls blank");
    }
    public void validateConfiguration(StrategyApplyRequest r) {
        validate(r);
        if(!brokers.findById(Objects.requireNonNullElse(r.getBrokerCredentialsId(),-1L))
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId())
                        && Set.of("SIMULATOR","SHAREKHAN").contains(Objects.toString(b.getBrokerName(),"").toUpperCase(Locale.ROOT))).isPresent())
            throw new IllegalArgumentException("NG Pine requires an owned Simulator or Sharekhan account");
    }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest r){return apply(r,LocalDateTime.now(StrategySupport.MARKET_ZONE));}
    public synchronized StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now) {
        validateConfiguration(r);
        if(!policy.entryAllowed(now))return waiting(r,"Outside NG Pine entry hours or inside news blackout");
        var allRequests=requests.findBySourceIgnoreCaseAndStatusIn(SOURCE,List.of(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
                TriggeredTradeStatus.ENTRY_SUBMITTING,TriggeredTradeStatus.TRIGGERED,TriggeredTradeStatus.FAILED));
        if(allRequests.stream().filter(p->owned(r,p.getAppUserId(),p.getBrokerCredentialsId()))
                .anyMatch(p->p.getStatus()!=TriggeredTradeStatus.FAILED || uncertain(p.getReason())))
            return waiting(r,"A pending or uncertain entry requires reconciliation");
        var positions=setups.findBySource(SOURCE).stream().filter(t->owned(r,t.getAppUserId(),t.getBrokerCredentialsId())).toList();
        if(positions.stream().anyMatch(t->!closed(t) || uncertain(t.getReason())))return waiting(r,"An existing option position must close before another entry");
        var previous=requests.findNaturalGasDailyRequests(SOURCE,r.getUserId(),r.getBrokerCredentialsId(),now.toLocalDate().atStartOfDay(),now.toLocalDate().plusDays(1).atStartOfDay());
        // Count durable fills too: request cleanup must never reset the daily cap.
        long attempts=previous.stream().filter(p->!org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON.equals(p.getReason())).count();
        long unlinked=positions.stream().filter(t->t.getEntryAt()!=null && t.getEntryAt().toLocalDate().equals(now.toLocalDate()))
                .filter(t->previous.stream().noneMatch(p->p.getId()!=null && p.getId().equals(t.getTriggerRequestId()))).count();
        if(attempts+unlinked>=policy.getMaxEntries())return waiting(r,"NG Pine daily entry limit reached");
        if(previous.stream().anyMatch(p->p.getStatus()==TriggeredTradeStatus.EXECUTED && positions.stream().noneMatch(t->Objects.equals(t.getTriggerRequestId(),p.getId()))))
            return waiting(r,"An executed request has no confirmed fill record; reconcile before re-entry");
        var rows=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX",r.getSymbol());
        final NaturalGasContracts.Pair pair;
        try {pair=NaturalGasContracts.resolve(rows,now.toLocalDate(),r.getSymbol());}
        catch(IllegalArgumentException missing){return waiting(r,missing.getMessage());}
        var future=pair.future();
        if(future.getTickSize()==null || !Double.isFinite(future.getTickSize()) || future.getTickSize()<=0)return waiting(r,"Futures tick metadata unavailable");
        data.watch(future.getScripCode());execution.warmUpSpotLtp(future,"NG Pine exact futures");
        String broker=brokers.findById(r.getBrokerCredentialsId()).orElseThrow().getBrokerName();
        var candles=dataPolicy.sourceForBroker(broker)==NaturalGasCandleSource.SHOONYA_TPS?data.shoonyaCandles(future,now):data.candles(future.getScripCode(),now);
        var signal=NaturalGasPineSignals.inspect(candles,now,policy);
        if(!signal.ready() || !signal.fresh(now) || signal.side()==null)return waiting(r,"Waiting for a fresh qualified futures signal; "+signal.reason());
        if(signal.end().minusMinutes(5).toLocalTime().isBefore(policy.getEntryStart()))return waiting(r,"Signal candle began before entry window");
        var activity=previous.stream().filter(p->!org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON.equals(p.getReason()))
                .map(TriggerTradeRequestEntity::getCreatedAt).toList();
        if(activity.stream().anyMatch(t->t==null || !signal.end().isAfter(t)))return waiting(r,"Require a new completed signal after the last attempt");
        if(positions.stream().map(TriggeredTradeSetupEntity::getExitedAt).filter(Objects::nonNull).anyMatch(t->!signal.end().isAfter(t)))
            return waiting(r,"Require a new completed signal after the last exit");
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        var fq=quotes.getSnapshot(future.getScripCode()).orElse(null);
        if(!freshFuture(fq,instant,future.getScripCode()))return waiting(r,"Waiting for fresh exact-contract Sharekhan futures price");
        double anchor=fq.getLastTradedPrice();
        // ATR is frozen at the decision bar. The futures reference is captured at option admission,
        // not shifted by differences in OPTION premium between order submission and fill.
        var levels=levels(signal.side(),anchor,signal.atr(),future.getTickSize(),policy);
        var option=NaturalGasContracts.atm(rows,pair.optionExpiry(),signal.side(),anchor,r.getSymbol());
        var t=new TriggerRequest();t.setInstrument(r.getSymbol());t.setExchange("MX");t.setOptionType(signal.side());
        t.setExpiry(option.getExpiry());t.setStrikePrice(option.getStrikePrice());t.setUserId(r.getUserId());t.setBrokerCredentialsId(r.getBrokerCredentialsId());
        t.setSource(SOURCE);t.setAction("BUY");t.setLots(r.getLots());t.setQuantity(r.getLots());t.setIntraday(true);t.setTslEnabled(false);
        t.setSpotScripCode(future.getScripCode());t.setUseSpotPrice(false);t.setUseSpotForEntry(false);t.setUseSpotForSl(true);t.setUseSpotForTarget(true);
        execution.warmUpOptionLtp(t,"NG Pine ATM option");
        var q=quotes.getSnapshot(option.getScripCode()).orElse(null);
        if(!NaturalGasAtmStrategy.usableBook(q,instant,option.getScripCode()) || q.getBestBid()<=0)return waiting(r,"Waiting for fresh coherent ATM option book with spread at most 2%");
        t.setEntryPrice(round(q.getBestAsk(),option.getTickSize(),RoundingMode.CEILING));t.setStopLoss(levels.stop());t.setTarget1(levels.target());
        var trade=support.executeTriggeredTrade(t);
        return StrategyApplyResponse.builder().templateId(TEMPLATE).symbol(r.getSymbol()).direction(signal.side()).status("triggered")
                .message("Buy ATM "+signal.side()+"; FUTURES reference="+anchor+", SL="+levels.stop()+", target="+levels.target()+"; option fill/P&L are separate. "+signal.reason())
                .triggerRequest(t).tradeRequest(trade).breakoutClose(signal.close()).build();
    }
    public record Levels(double stop,double target) { }
    public static Levels levels(String side,double anchor,double atr,double tick,NaturalGasPinePolicy p) {
        double risk=Math.ceil(atr*p.getStopAtr()/tick-1e-10)*tick;
        double reward=Math.max(1,Math.round(risk/tick*p.getRewardR()))*tick;
        boolean ce="CE".equals(side);
        var result=new Levels(round(anchor+(ce?-risk:risk),tick,ce?RoundingMode.FLOOR:RoundingMode.CEILING),
                round(anchor+(ce?reward:-reward),tick,ce?RoundingMode.CEILING:RoundingMode.FLOOR));
        if(!Double.isFinite(result.stop) || !Double.isFinite(result.target) || result.stop<=0 || result.target<=0)
            throw new IllegalArgumentException("Invalid futures bracket");
        return result;
    }
    public static boolean freshFuture(QuoteCacheService.QuoteSnapshot q,Instant now,Integer code) {
        return q!=null && q.getLastTradedPrice()!=null && Double.isFinite(q.getLastTradedPrice()) && q.getLastTradedPrice()>0
                && q.getLastLtpAt()!=null && !q.getLastLtpAt().isAfter(now) && !q.getLastLtpAt().isBefore(now.minusSeconds(15))
                && "SHAREKHAN_WS".equals(q.getLtpSource()) && ("scrip:"+code).equals(q.getLtpProviderIdentity());
    }
    static boolean closed(TriggeredTradeSetupEntity t) {return (t.getActualEntryPrice()==null || t.getStatus()==TriggeredTradeStatus.EXITED_SUCCESS || t.getStatus()==TriggeredTradeStatus.COMPLETED) && t.getStatus()!=null && Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED,
            TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED,TriggeredTradeStatus.FAILED).contains(t.getStatus());}
    private static boolean owned(StrategyApplyRequest r,Long user,Long broker){return Objects.equals(r.getUserId(),user)&&Objects.equals(r.getBrokerCredentialsId(),broker);}
    private static boolean uncertain(String reason){return reason!=null && (reason.contains("UNKNOWN") || reason.contains("UNCONFIRMED") || reason.contains("RECONCILIATION_REQUIRED"));}
    private StrategyApplyResponse waiting(StrategyApplyRequest r,String message){return support.waiting(META,r.getSymbol(),message);}
    private static double round(double x,double tick,RoundingMode mode){return BigDecimal.valueOf(x).divide(BigDecimal.valueOf(tick),0,mode).multiply(BigDecimal.valueOf(tick)).doubleValue();}
}
