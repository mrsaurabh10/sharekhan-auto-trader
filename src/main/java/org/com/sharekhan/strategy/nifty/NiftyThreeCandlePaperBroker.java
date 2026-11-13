package org.com.sharekhan.strategy.nifty;

import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Owns only this strategy's paper fills. There is no BrokerService or live-order dependency. */
@Service @Slf4j
public class NiftyThreeCandlePaperBroker {
    private final EntityManager em;
    private final TriggeredTradeSetupRepository trades;
    private final TriggerTradeRequestRepository requests;
    private final QuoteCacheService quotes;
    private final StrategyRunCoordinator runs;
    private final TradeAuditService audit;
    private final Clock clock;
    @Autowired
    public NiftyThreeCandlePaperBroker(EntityManager em,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests,
                                       QuoteCacheService quotes,StrategyRunCoordinator runs,TradeAuditService audit){
        this(em,trades,requests,quotes,runs,audit,Clock.system(NiftyThreeCandleRules.IST));
    }
    NiftyThreeCandlePaperBroker(EntityManager em,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests,
                                QuoteCacheService quotes,StrategyRunCoordinator runs,TradeAuditService audit,Clock clock){
        this.em=em;this.trades=trades;this.requests=requests;this.quotes=quotes;this.runs=runs;this.audit=audit;this.clock=clock;
    }
    private static boolean terminal(TriggeredTradeStatus status){
        return status!=null && Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED,
                TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED).contains(status);
    }
    private List<TriggeredTradeSetupEntity> owned(Long user,Long broker){
        return trades.findBySource(NiftyThreeCandleRules.SOURCE).stream()
                .filter(t->Objects.equals(t.getAppUserId(),user) && Objects.equals(t.getBrokerCredentialsId(),broker)).toList();
    }
    private boolean isToday(TriggeredTradeSetupEntity t,LocalDate day){
        var activity=t.getExitedAt()!=null?t.getExitedAt():t.getEntryAt();
        return activity!=null && activity.toLocalDate().equals(day);
    }
    static double netAt(TriggeredTradeSetupEntity t,double bid){
        if(t.getActualEntryPrice()==null || t.getQuantity()==null || t.getQuantity()<=0)throw new IllegalStateException("Paper fill identity unavailable");
        var mark=TriggeredTradeSetupEntity.builder().actualEntryPrice(t.getActualEntryPrice()).exitPrice(bid).quantity(t.getQuantity())
                .lots(t.getLots()).entryAction("BUY").optionType(t.getOptionType()).exchange(t.getExchange()).intraday(true)
                .pnl((bid-t.getActualEntryPrice())*t.getQuantity()).source(NiftyThreeCandleRules.SOURCE).build();
        var charges=TradeCostCalculator.calculateCharges(mark);
        if(charges==null)throw new IllegalStateException("Paper charge estimate unavailable");
        return charges.effectivePnl();
    }
    private double realized(List<TriggeredTradeSetupEntity> rows,LocalDate day){
        double net=0;
        for(var t:rows)if(isToday(t,day) && terminal(t.getStatus())){
            if(t.getExitPrice()==null)throw new IllegalStateException("An earlier paper exit needs reconciliation");
            net+=netAt(t,t.getExitPrice());
        }
        return net;
    }
    private BrokerCredentialsEntity simulator(Long user,Long id){
        var b=em.find(BrokerCredentialsEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(b==null || !Objects.equals(user,b.getAppUserId()) || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Boolean.TRUE.equals(b.getActive()))
            throw new IllegalArgumentException("Choose an active owned Simulator account");
        return b;
    }
    @Transactional
    public TriggerTradeRequestEntity open(StrategyApplyRequest r,NiftyThreeCandleRules.Signal signal,ScriptMasterEntity option,int spotCode,List<ScriptMasterEntity> contracts){
        NiftyThreeCandleStrategy.validate(r);
        simulator(r.getUserId(),r.getBrokerCredentialsId()); // DB row lock serializes CE/PE, subscriptions and instances for this account.
        var now=LocalDateTime.now(clock);
        if(!signal.inWindow(now))throw new IllegalStateException("The fourth-candle entry window has expired");
        var prior=owned(r.getUserId(),r.getBrokerCredentialsId());
        if(prior.stream().anyMatch(t->!terminal(t.getStatus())))throw new IllegalStateException("A CE/PE paper position is already open or unresolved");
        if(prior.stream().anyMatch(t->isToday(t,now.toLocalDate()) && t.getComment()!=null && t.getComment().contains("signal="+signal.key()+";")))
            throw new IllegalStateException("This signal has already been traded");
        if(prior.stream().anyMatch(t->t.getEntryAt()!=null && !t.getEntryAt().isAfter(signal.end())
                && t.getExitedAt()!=null && !t.getExitedAt().isBefore(signal.end())))
            throw new IllegalStateException("This pattern completed while the previous position was open");
        if(prior.stream().anyMatch(t->isToday(t,now.toLocalDate()) && lossExit(t.getExitReason()))
                || realized(prior,now.toLocalDate())<=-NiftyThreeCandleRules.DAILY_LOSS)
            throw new IllegalStateException("₹5,000 combined daily paper loss limit reached; entries stopped for today");
        var spot=quotes.getSnapshot(spotCode).orElse(null);
        var book=quotes.getSnapshot(option.getScripCode()).orElse(null);
        var instant=clock.instant();
        var observedAt=LocalDateTime.ofInstant(instant,NiftyThreeCandleRules.IST);
        if(!signal.inWindow(observedAt))throw new IllegalStateException("The fourth-candle entry window has expired");
        if(!NiftyThreeCandleRules.freshSpot(spot,instant,spotCode))
            throw new IllegalStateException("Waiting for fresh exact Nifty spot quote");
        if(!NiftyThreeCandleRules.freshBook(book,instant,option.getScripCode()))
            throw new IllegalStateException("Waiting for fresh exact option bid/ask; scrip="+option.getScripCode());
        if(option.getLotSize()==null || option.getLotSize()<=0 || option.getTickSize()==null || !Double.isFinite(option.getTickSize()) || option.getTickSize()<=0
                || !signal.side().equals(option.getOptionType()) || !"NIFTY".equalsIgnoreCase(option.getTradingSymbol())
                || !Set.of("NF","NFO").contains(Objects.toString(option.getExchange(),"")))throw new IllegalArgumentException("Invalid Nifty option contract metadata");
        double entry=spot.getLastTradedPrice();
        if(!Objects.equals(option.getScripCode(),NiftyThreeCandleStrategy.contract(contracts,signal.side(),entry,now.toLocalDate()).getScripCode()))
            throw new IllegalStateException("ATM contract changed; waiting for its exact book");
        if(!signal.crossed(entry) || (signal.pe()?entry>=signal.stop():entry<=signal.stop()))throw new IllegalStateException("Waiting for the spot breakout");
        long qty=option.getLotSize();
        if(book.getAskQuantity()==null || !Double.isFinite(book.getAskQuantity()) || book.getAskQuantity()<qty)
            throw new IllegalStateException("Paper entry requires one lot displayed at the ask");
        var trigger=new TriggerRequest();trigger.setUserId(r.getUserId());trigger.setBrokerCredentialsId(r.getBrokerCredentialsId());
        runs.attach(trigger);
        if(trigger.getStrategyRunId()==null)throw new IllegalStateException("A tracked strategy run is required for paper entries");
        // Keep subscription cancellation / Close All serialized through this transaction's commit.
        if(r.getSubscriptionId()!=null){
            var sub=em.find(StrategySubscriptionEntity.class,r.getSubscriptionId(),LockModeType.PESSIMISTIC_WRITE);
            if(sub==null || !Objects.equals(sub.getAppUserId(),r.getUserId()) || !Objects.equals(sub.getBrokerCredentialsId(),r.getBrokerCredentialsId())
                    || !NiftyThreeCandleRules.TEMPLATE.equals(sub.getTemplateId()) || !List.of("ACTIVE","TRIGGERED").contains(sub.getStatus()))
                throw new IllegalStateException("Strategy subscription paused or cancelled before entry");
        }
        var run=em.find(StrategyRun.class,trigger.getStrategyRunId(),LockModeType.PESSIMISTIC_WRITE);
        if(run==null || !"OPEN".equals(run.getStatus()) || !Objects.equals(run.getAppUserId(),r.getUserId())
                || !Objects.equals(run.getBrokerCredentialsId(),r.getBrokerCredentialsId()))throw new IllegalStateException("Strategy run is closing or closed");
        var fillInstant=clock.instant();
        if(!signal.inWindow(LocalDateTime.ofInstant(fillInstant,NiftyThreeCandleRules.IST)))
            throw new IllegalStateException("The fourth-candle entry window has expired");
        if(!NiftyThreeCandleRules.freshSpot(spot,fillInstant,spotCode) || !NiftyThreeCandleRules.freshBook(book,fillInstant,option.getScripCode()))
            throw new IllegalStateException("Entry quotes expired while acquiring strategy locks; waiting for fresh data");
        var req=TriggerTradeRequestEntity.builder().strategyRunId(trigger.getStrategyRunId()).appUserId(r.getUserId()).brokerCredentialsId(r.getBrokerCredentialsId())
                .symbol("NIFTY").scripCode(option.getScripCode()).exchange(option.getExchange()).instrumentType(option.getInstrumentType())
                .strikePrice(option.getStrikePrice()).optionType(signal.side()).expiry(option.getExpiry()).entryAction("BUY")
                .quantity(qty).lots(1).entryPrice(entry).stopLoss(signal.stop()).target1(signal.target(entry)).tslEnabled(false)
                .useSpotPrice(true).useSpotForEntry(true).useSpotForSl(true).useSpotForTarget(true).spotScripCode(spotCode)
                .intraday(true).source(NiftyThreeCandleRules.SOURCE).status(TriggeredTradeStatus.EXECUTED).createdAt(now)
                .reason("NIFTY_3C_PAPER_ASK_FILL").comment("signal="+signal.key()+"; option ask fill; CE researched / PE mirrored; fees="+"legacy-option-lot-estimate").build();
        req=requests.save(req);
        var trade=TriggeredTradeSetupEntity.builder().strategyRunId(trigger.getStrategyRunId()).triggerRequestId(req.getId())
                .appUserId(r.getUserId()).brokerCredentialsId(r.getBrokerCredentialsId()).symbol("NIFTY").scripCode(option.getScripCode())
                .exchange(option.getExchange()).instrumentType(option.getInstrumentType()).strikePrice(option.getStrikePrice()).optionType(signal.side()).expiry(option.getExpiry())
                .quantity(qty).lots(1).originalLots(1).entryAction("BUY").entryPrice(entry).actualEntryPrice(book.getBestAsk())
                .stopLoss(signal.stop()).target1(signal.target(entry)).tslEnabled(false).useSpotPrice(true).useSpotForEntry(true).useSpotForSl(true).useSpotForTarget(true)
                .spotScripCode(spotCode).intraday(true).source(NiftyThreeCandleRules.SOURCE).status(TriggeredTradeStatus.EXECUTED)
                .triggeredAt(now).entryAt(now).orderId("SIM-N3C-"+UUID.randomUUID()).reason(req.getReason()).comment(req.getComment()).build();
        trade=trades.saveAndFlush(trade);
        record(trade,"PAPER_ENTRY",signal.side(),"spot="+entry+", stop="+signal.stop()+", target="+signal.target(entry)+", ask="+book.getBestAsk()+", quantity="+qty+", signal="+signal.key()+", ema="+signal.ema()+", priorEma="+signal.priorEma());
        return req;
    }
    static boolean lossExit(String reason){return "NIFTY_3C_DAILY_LOSS".equals(reason) || "STRATEGY_MAX_LOSS".equals(reason);}
    @Transactional
    public void evaluate(Long id){
        var t=em.find(TriggeredTradeSetupEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(t==null || !NiftyThreeCandleRules.owns(t.getSource()) || terminal(t.getStatus()))return;
        var b=em.find(BrokerCredentialsEntity.class,t.getBrokerCredentialsId());
        if(b==null || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Objects.equals(t.getAppUserId(),b.getAppUserId()))return;
        var book=quotes.getSnapshot(t.getScripCode()).orElse(null);
        var spot=quotes.getSnapshot(t.getSpotScripCode()).orElse(null);
        var instant=clock.instant();
        var now=LocalDateTime.ofInstant(instant,NiftyThreeCandleRules.IST);
        boolean eod=t.getEntryAt().toLocalDate().isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(LocalTime.of(15,30));
        String reason=t.getExitReason(); // Persist the first observed trigger while awaiting an executable book.
        if(reason==null && eod)reason="NIFTY_3C_SESSION_CLOSE";
        boolean usable=NiftyThreeCandleRules.freshBook(book,instant,t.getScripCode());
        if(usable){
            double daily=realized(owned(t.getAppUserId(),t.getBrokerCredentialsId()),now.toLocalDate())+netAt(t,book.getBestBid());
            if(daily<=-NiftyThreeCandleRules.DAILY_LOSS)reason="NIFTY_3C_DAILY_LOSS";
        }
        if(reason==null && NiftyThreeCandleRules.freshSpot(spot,instant,t.getSpotScripCode())){
            double p=spot.getLastTradedPrice();boolean pe="PE".equals(t.getOptionType());
            if(pe?p>=t.getStopLoss():p<=t.getStopLoss())reason="NIFTY_3C_STOP";
            else if(pe?p<=t.getTarget1():p>=t.getTarget1())reason="NIFTY_3C_TARGET";
        }
        if(reason!=null){
            t.setExitReason(reason);trades.saveAndFlush(t);
            if(lossExit(reason) && t.getStrategyRunId()!=null)runs.requestClose(t.getStrategyRunId(),reason);
            if(usable)finish(t,book,spot,reason,now);
        }
    }
    /** Used by run Close All and ordinary manual square-off; never use an entered/manual exit price. */
    @Transactional
    public void close(Long id,String reason){
        var t=em.find(TriggeredTradeSetupEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(t==null || !NiftyThreeCandleRules.owns(t.getSource()) || terminal(t.getStatus()))return;
        var b=em.find(BrokerCredentialsEntity.class,t.getBrokerCredentialsId());
        if(b==null || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Objects.equals(b.getAppUserId(),t.getAppUserId()))throw new IllegalStateException("Not an owned paper position");
        // Close All is also an observed exit trigger; retain it through unavailable/insufficient books.
        if(t.getExitReason()==null){t.setExitReason(reason);trades.saveAndFlush(t);}
        var q=quotes.getSnapshot(t.getScripCode()).orElse(null);
        if(!NiftyThreeCandleRules.freshBook(q,clock.instant(),t.getScripCode()))return;
        finish(t,q,quotes.getSnapshot(t.getSpotScripCode()).orElse(null),t.getExitReason()!=null?t.getExitReason():reason,LocalDateTime.now(clock));
    }
    private void finish(TriggeredTradeSetupEntity t,QuoteCacheService.QuoteSnapshot book,QuoteCacheService.QuoteSnapshot spot,String reason,LocalDateTime now){
        if(book.getBidQuantity()==null || !Double.isFinite(book.getBidQuantity()) || book.getBidQuantity()<t.getQuantity())return;
        t.setExitPrice(book.getBestBid());t.setPnl((book.getBestBid()-t.getActualEntryPrice())*t.getQuantity());
        t.setExitReason(reason);t.setExitedAt(now);t.setExitOrderId("SIM-N3C-EXIT-"+UUID.randomUUID());t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        trades.saveAndFlush(t);
        Double spotExit=spot!=null && NiftyThreeCandleRules.freshSpot(spot,clock.instant(),t.getSpotScripCode())?spot.getLastTradedPrice():null;
        double risk=Math.abs(t.getEntryPrice()-t.getStopLoss());
        Double r=spotExit==null?null:("PE".equals(t.getOptionType())?t.getEntryPrice()-spotExit:spotExit-t.getEntryPrice())/risk;
        record(t,"PAPER_EXIT",reason,"spot="+spotExit+", spotR="+r+", optionBid="+book.getBestBid()+", optionGross="+t.getPnl()+", estimatedNet="+t.getEffectivePnl()+", costModel=legacy-option-lot-estimate");
    }
    private void record(TriggeredTradeSetupEntity t,String event,String reason,String detail){
        audit.record(TradeAuditEventEntity.builder().occurredAt(LocalDateTime.now(clock)).appUserId(t.getAppUserId()).tradeId(t.getId())
                .triggerRequestId(t.getTriggerRequestId()).strategyRunId(t.getStrategyRunId()).strategyId(NiftyThreeCandleRules.TEMPLATE)
                .source(t.getSource()).symbol(t.getSymbol()).optionType(t.getOptionType()).eventType(event).outcome("SIMULATOR")
                .reason(reason).details(detail).build());
    }
}
