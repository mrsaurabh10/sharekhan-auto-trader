package org.com.sharekhan.strategy.adxdmi;

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
public class AdxDmiPaperBroker {
    private final EntityManager em;
    private final TriggeredTradeSetupRepository trades;
    private final TriggerTradeRequestRepository requests;
    private final AdxDmiMarketData quotes;
    private final StrategyRunCoordinator runs;
    private final TradeAuditService audit;
    private final Clock clock;
    @Autowired
    public AdxDmiPaperBroker(EntityManager em,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests,
                                       AdxDmiMarketData quotes,StrategyRunCoordinator runs,TradeAuditService audit){
        this(em,trades,requests,quotes,runs,audit,Clock.system(AdxDmiRules.IST));
    }
    AdxDmiPaperBroker(EntityManager em,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests,
                                AdxDmiMarketData quotes,StrategyRunCoordinator runs,TradeAuditService audit,Clock clock){
        this.em=em;this.trades=trades;this.requests=requests;this.quotes=quotes;this.runs=runs;this.audit=audit;this.clock=clock;
    }
    private static boolean terminal(TriggeredTradeStatus status){
        return status!=null && Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED,
                TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED).contains(status);
    }
    private List<TriggeredTradeSetupEntity> owned(Long user,Long broker,String symbol){
        return trades.findBySource(AdxDmiRules.SOURCE).stream()
                .filter(t->symbol.equalsIgnoreCase(t.getSymbol()) && Objects.equals(t.getAppUserId(),user) && Objects.equals(t.getBrokerCredentialsId(),broker)).toList();
    }
    private boolean isToday(TriggeredTradeSetupEntity t,LocalDate day){
        var activity=t.getExitedAt()!=null?t.getExitedAt():t.getEntryAt();
        return activity!=null && activity.toLocalDate().equals(day);
    }
    private BrokerCredentialsEntity simulator(Long user,Long id){
        var b=em.find(BrokerCredentialsEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(b==null || !Objects.equals(user,b.getAppUserId()) || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Boolean.TRUE.equals(b.getActive()))
            throw new IllegalArgumentException("Choose an active owned Simulator account");
        return b;
    }
    @Transactional
    public TriggerTradeRequestEntity open(StrategyApplyRequest r,AdxDmiRules.Signal signal,ScriptMasterEntity option,int spotCode,List<ScriptMasterEntity> contracts){
        AdxDmiStrategy.validate(r);
        simulator(r.getUserId(),r.getBrokerCredentialsId()); // DB row lock serializes CE/PE, subscriptions and instances for this account.
        var now=LocalDateTime.now(clock);var instant=clock.instant();
        if(!signal.inWindow(now))throw new IllegalStateException("The next-candle entry window has expired");
        final var entryDay=now.toLocalDate();
        var prior=owned(r.getUserId(),r.getBrokerCredentialsId(),r.getSymbol());
        if(prior.stream().anyMatch(t->!terminal(t.getStatus())))throw new IllegalStateException("A CE/PE paper position is already open or unresolved");
        if(prior.stream().anyMatch(t->isToday(t,entryDay) && t.getComment()!=null && t.getComment().contains("signal="+signal.key()+";")))
            throw new IllegalStateException("This signal has already been traded");
        if(prior.stream().anyMatch(t->t.getEntryAt()!=null && !t.getEntryAt().isAfter(signal.end())
                && t.getExitedAt()!=null && !t.getExitedAt().isBefore(signal.end())))
            throw new IllegalStateException("Signal completed while the previous position was open");
        if(prior.stream().anyMatch(t->activityInSignalBar(t.getEntryAt(),signal.end()) || activityInSignalBar(t.getExitedAt(),signal.end())))
            throw new IllegalStateException("No signal from a candle containing a previous fill");
        var spot=quotes.spot(spotCode,AdxDmiRules.cashExchange(option.getExchange()));
        var book=quotes.book(option.getScripCode(),option.getExchange());
        now=LocalDateTime.now(clock);instant=clock.instant();
        if(!signal.inWindow(now))throw new IllegalStateException("The next-candle entry window has expired");
        if(!quotes.freshSpot(AdxDmiRules.cashExchange(option.getExchange()),spot,instant,spotCode) || !quotes.freshBook(option.getExchange(),book,instant,option.getScripCode()))
            throw new IllegalStateException("Waiting for fresh exact index spot and option bid/ask: spot="+quotes.reason(spotCode,AdxDmiRules.cashExchange(option.getExchange()),false)+", book="+quotes.reason(option.getScripCode(),option.getExchange(),true));
        // Paper fills use the fresh quoted ask/bid directly; no option order-price rounding is performed.
        if(option.getLotSize()==null || option.getLotSize()<=0 || option.getTickSize()!=null && (!Double.isFinite(option.getTickSize()) || option.getTickSize()<=0)
                || !signal.side().equals(option.getOptionType()) || !r.getSymbol().equalsIgnoreCase(option.getTradingSymbol())
                || !Set.of("NF","BF").contains(Objects.toString(option.getExchange(),"")))throw new IllegalArgumentException("Invalid index option contract metadata");
        double entry=spot.getLastTradedPrice();
        if(!Objects.equals(option.getScripCode(),AdxDmiStrategy.contract(contracts,r.getSymbol(),option.getExchange(),signal.side(),entry,now.toLocalDate()).getScripCode()))
            throw new IllegalStateException("ATM contract changed; waiting for its exact book");
        if(!signal.crossed(entry) || (signal.pe()?entry>=signal.stop():entry<=signal.stop()))throw new IllegalStateException("Waiting for the spot breakout");
        long qty=Math.multiplyExact(3L,option.getLotSize());
        if(book.getAskQuantity()==null || !Double.isFinite(book.getAskQuantity()) || book.getAskQuantity()<qty)
            throw new IllegalStateException("Paper entry requires three lots displayed at the ask");
        var trigger=new TriggerRequest();trigger.setUserId(r.getUserId());trigger.setBrokerCredentialsId(r.getBrokerCredentialsId());
        runs.attach(trigger);
        if(trigger.getStrategyRunId()==null)throw new IllegalStateException("A tracked strategy run is required for paper entries");
        // Keep subscription cancellation / Close All serialized through this transaction's commit.
        if(r.getSubscriptionId()!=null){
            var sub=em.find(StrategySubscriptionEntity.class,r.getSubscriptionId(),LockModeType.PESSIMISTIC_WRITE);
            if(sub==null || !Objects.equals(sub.getAppUserId(),r.getUserId()) || !Objects.equals(sub.getBrokerCredentialsId(),r.getBrokerCredentialsId())
                    || !AdxDmiRules.TEMPLATE.equals(sub.getTemplateId()) || !List.of("ACTIVE","TRIGGERED").contains(sub.getStatus()))
                throw new IllegalStateException("Strategy subscription paused or cancelled before entry");
        }
        var run=em.find(StrategyRun.class,trigger.getStrategyRunId(),LockModeType.PESSIMISTIC_WRITE);
        if(run==null || !"OPEN".equals(run.getStatus()) || !Objects.equals(run.getAppUserId(),r.getUserId())
                || !Objects.equals(run.getBrokerCredentialsId(),r.getBrokerCredentialsId()))throw new IllegalStateException("Strategy run is closing or closed");
        now=LocalDateTime.now(clock);instant=clock.instant();
        if(!signal.inWindow(now) || !quotes.freshSpot(AdxDmiRules.cashExchange(option.getExchange()),spot,instant,spotCode)
                || !quotes.freshBook(option.getExchange(),book,instant,option.getScripCode()))
            throw new IllegalStateException("Entry window or exact quote expired while waiting for account locks");
        var req=TriggerTradeRequestEntity.builder().strategyRunId(trigger.getStrategyRunId()).appUserId(r.getUserId()).brokerCredentialsId(r.getBrokerCredentialsId())
                .symbol(r.getSymbol()).scripCode(option.getScripCode()).exchange(option.getExchange()).instrumentType(option.getInstrumentType())
                .strikePrice(option.getStrikePrice()).optionType(signal.side()).expiry(option.getExpiry()).entryAction("BUY")
                .quantity(qty).lots(3).entryPrice(entry).stopLoss(signal.stop()).target1(signal.target(entry,1)).target2(signal.target(entry,2)).target3(signal.target(entry,3)).tslEnabled(false)
                .useSpotPrice(true).useSpotForEntry(true).useSpotForSl(true).useSpotForTarget(true).spotScripCode(spotCode)
                .intraday(true).source(AdxDmiRules.SOURCE).status(TriggeredTradeStatus.EXECUTED).createdAt(now)
                .reason("ADX_DMI_PAPER_ASK_FILL").comment("signal="+signal.key()+"; original fixed spot SL; deltaRiskEstimate="+(signal.risk(entry)*AdxDmiRules.DELTA)+"; fees=legacy-option-lot-estimate").build();
        req=requests.save(req);
        for(int leg=1;leg<=3;leg++){
            var trade=TriggeredTradeSetupEntity.builder().strategyRunId(trigger.getStrategyRunId()).triggerRequestId(req.getId())
                .appUserId(r.getUserId()).brokerCredentialsId(r.getBrokerCredentialsId()).symbol(r.getSymbol()).scripCode(option.getScripCode())
                .exchange(option.getExchange()).instrumentType(option.getInstrumentType()).strikePrice(option.getStrikePrice()).optionType(signal.side()).expiry(option.getExpiry())
                .quantity((long)option.getLotSize()).lots(1).originalLots(1).entryAction("BUY").entryPrice(entry).actualEntryPrice(book.getBestAsk())
                .stopLoss(signal.stop()).target1(signal.target(entry,leg)).tslEnabled(false).useSpotPrice(true).useSpotForEntry(true).useSpotForSl(true).useSpotForTarget(true)
                .spotScripCode(spotCode).intraday(true).source(AdxDmiRules.SOURCE).status(TriggeredTradeStatus.EXECUTED)
                .triggeredAt(now).entryAt(now).orderId("SIM-ADXDMI-"+UUID.randomUUID()).reason(req.getReason()).comment(req.getComment()+"; leg="+leg+";").build();
        trade=trades.saveAndFlush(trade);
        record(trade,"PAPER_ENTRY",signal.side(),"spot="+entry+", stop="+signal.stop()+", target="+signal.target(entry,leg)+", ask="+book.getBestAsk()+", quantity="+option.getLotSize()+", signal="+signal.key()+", DMI="+signal.reading());
        }
        return req;
    }
    @Transactional
    public void evaluate(Long id){
        var t=em.find(TriggeredTradeSetupEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(t==null || !AdxDmiRules.owns(t.getSource()) || terminal(t.getStatus()))return;
        var b=em.find(BrokerCredentialsEntity.class,t.getBrokerCredentialsId(),LockModeType.PESSIMISTIC_WRITE);
        if(b==null || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Objects.equals(t.getAppUserId(),b.getAppUserId()))return;
        var now=LocalDateTime.now(clock);var instant=clock.instant();
        var book=quotes.book(t.getScripCode(),t.getExchange());
        var spot=quotes.spot(t.getSpotScripCode(),AdxDmiRules.cashExchange(t.getExchange()));
        now=LocalDateTime.now(clock);instant=clock.instant();
        boolean eod=t.getEntryAt().toLocalDate().isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(AdxDmiRules.SQUARE_OFF);
        String reason=t.getExitReason(); // Persist the first observed trigger while awaiting an executable book.
        if(reason==null && eod)reason="ADX_DMI_SESSION_CLOSE";
        boolean usable=quotes.freshBook(t.getExchange(),book,instant,t.getScripCode());
        if(reason==null && quotes.freshSpot(AdxDmiRules.cashExchange(t.getExchange()),spot,instant,t.getSpotScripCode())){
            double p=spot.getLastTradedPrice();boolean pe="PE".equals(t.getOptionType());
            if(pe?p>=t.getStopLoss():p<=t.getStopLoss())reason="ADX_DMI_STOP";
            else if(pe?p<=t.getTarget1():p>=t.getTarget1())reason="ADX_DMI_TARGET";
        }
        if(reason!=null){
            t.setExitReason(reason);trades.saveAndFlush(t);
            if(usable)finish(t,book,spot,reason,now);
        }
    }
    /** Used by run Close All and ordinary manual square-off; never use an entered/manual exit price. */
    @Transactional
    public void close(Long id,String reason){
        var t=em.find(TriggeredTradeSetupEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(t==null || !AdxDmiRules.owns(t.getSource()) || terminal(t.getStatus()))return;
        var b=em.find(BrokerCredentialsEntity.class,t.getBrokerCredentialsId(),LockModeType.PESSIMISTIC_WRITE);
        if(b==null || !"SIMULATOR".equalsIgnoreCase(b.getBrokerName()) || !Objects.equals(b.getAppUserId(),t.getAppUserId()))throw new IllegalStateException("Not an owned paper position");
        // Close All is also an observed exit trigger; retain it through unavailable/insufficient books.
        if(t.getExitReason()==null){t.setExitReason(reason);trades.saveAndFlush(t);}
        var q=quotes.book(t.getScripCode(),t.getExchange());
        if(!quotes.freshBook(t.getExchange(),q,clock.instant(),t.getScripCode()))return;
        finish(t,q,quotes.spot(t.getSpotScripCode(),AdxDmiRules.cashExchange(t.getExchange())),t.getExitReason()!=null?t.getExitReason():reason,LocalDateTime.now(clock));
    }
    private static boolean activityInSignalBar(LocalDateTime at,LocalDateTime end){return at!=null && !at.isBefore(end.minusMinutes(5)) && at.isBefore(end);}
    private void finish(TriggeredTradeSetupEntity t,QuoteCacheService.QuoteSnapshot book,QuoteCacheService.QuoteSnapshot spot,String reason,LocalDateTime now){
        if(!quotes.freshBook(t.getExchange(),book,clock.instant(),t.getScripCode()))return;
        if(book.getBidQuantity()==null || !Double.isFinite(book.getBidQuantity()))return;
        long consumed=owned(t.getAppUserId(),t.getBrokerCredentialsId(),t.getSymbol()).stream()
                .filter(other->terminal(other.getStatus()) && Objects.equals(other.getScripCode(),t.getScripCode()) && other.getExitedAt()!=null
                        && !other.getExitedAt().atZone(AdxDmiRules.IST).toInstant().isBefore(book.getLastBookAt()))
                .mapToLong(other->other.getQuantity()==null?0:other.getQuantity()).sum();
        if(book.getBidQuantity()-consumed<t.getQuantity())return;
        t.setExitPrice(book.getBestBid());t.setPnl((book.getBestBid()-t.getActualEntryPrice())*t.getQuantity());
        t.setExitReason(reason);t.setExitedAt(now);t.setExitOrderId("SIM-ADXDMI-EXIT-"+UUID.randomUUID());t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        trades.saveAndFlush(t);
        Double spotExit=spot!=null && quotes.freshSpot(AdxDmiRules.cashExchange(t.getExchange()),spot,clock.instant(),t.getSpotScripCode())?spot.getLastTradedPrice():null;
        double risk=Math.abs(t.getEntryPrice()-t.getStopLoss());
        Double r=spotExit==null?null:("PE".equals(t.getOptionType())?t.getEntryPrice()-spotExit:spotExit-t.getEntryPrice())/risk;
        record(t,"PAPER_EXIT",reason,"spot="+spotExit+", spotR="+r+", optionBid="+book.getBestBid()+", optionGross="+t.getPnl()+", estimatedNet="+t.getEffectivePnl()+", costModel=legacy-option-lot-estimate");
    }
    private void record(TriggeredTradeSetupEntity t,String event,String reason,String detail){
        audit.record(TradeAuditEventEntity.builder().occurredAt(LocalDateTime.now(clock)).appUserId(t.getAppUserId()).tradeId(t.getId())
                .triggerRequestId(t.getTriggerRequestId()).strategyRunId(t.getStrategyRunId()).strategyId(AdxDmiRules.TEMPLATE)
                .source(t.getSource()).symbol(t.getSymbol()).optionType(t.getOptionType()).eventType(event).outcome("SIMULATOR")
                .reason(reason).details(detail).build());
    }
}
