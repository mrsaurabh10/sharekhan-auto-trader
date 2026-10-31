package org.com.sharekhan.strategy.run;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.StrategySupport;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.util.*;

/** Aggregates full run membership before pagination; recovery never depends on the UI. */
@Slf4j @Service
public class StrategyRunService {
    private final StrategyRunRepository runs;
    private final TriggeredTradeSetupRepository trades;
    private final TriggerTradeRequestRepository requests;
    private final TradeExecutionService execution;
    private final StrategySubscriptionService subscriptions;
    private final StrategyRunRiskEngine risk=new StrategyRunRiskEngine();
    private static final Set<TriggeredTradeStatus> FLAT=EnumSet.of(TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.CANCELLED,TriggeredTradeStatus.FAILED,TriggeredTradeStatus.COMPLETED,TriggeredTradeStatus.EXITED_SUCCESS);
    private static final Set<TriggeredTradeStatus> EXITABLE=EnumSet.of(TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED);
    public StrategyRunService(StrategyRunRepository runs,TriggeredTradeSetupRepository trades,TriggerTradeRequestRepository requests,
                              TradeExecutionService execution,StrategySubscriptionService subscriptions) {
        this.runs=runs;this.trades=trades;this.requests=requests;this.execution=execution;this.subscriptions=subscriptions;
    }
    public record Pnl(BigDecimal realizedGross,BigDecimal openGross,BigDecimal totalGross,BigDecimal estimatedCosts,
                      BigDecimal estimatedNet,boolean marksFresh,int openPositions) { }
    public record View(StrategyRun run,Pnl pnl,List<TriggeredTradeSetupEntity> positions,int requests,String pnlBasis) { }
    public Page<View> list(Long userId,boolean admin,int page,int size) {
        Pageable pageable=PageRequest.of(Math.max(0,page),Math.min(50,Math.max(1,size)));
        Page<StrategyRun> selected=admin && userId==null?runs.findAllByOrderByCreatedAtDesc(pageable):runs.findByAppUserIdOrderByCreatedAtDesc(userId,pageable);
        return selected.map(this::view);
    }
    public View view(StrategyRun run) {
        var members=members(run);
        var values=pnl(members,Instant.now());
        boolean averaging=org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE.equalsIgnoreCase(run.getTemplateId());
        if(averaging && values.marksFresh() && values.totalGross()!=null && members.stream().allMatch(t->t.getTradeCost()==null || (Double.isFinite(t.getTradeCost()) && t.getTradeCost()>=0))) {
            double reserve=members.stream().filter(t->t.getActualEntryPrice()!=null && t.getQuantity()!=null && t.getQuantity()>0)
                    .mapToDouble(t->Math.max(org.com.sharekhan.strategy.mcx.NaturalGasAveragingBook.COST_PER_LOT,
                            t.getTradeCost()!=null && Double.isFinite(t.getTradeCost()) && t.getTradeCost()>=0?t.getTradeCost():0)).sum();
            var costs=money(BigDecimal.valueOf(reserve));
            values=new Pnl(values.realizedGross(),values.openGross(),values.totalGross(),costs,money(values.totalGross().subtract(costs)),true,values.openPositions());
        }
        if(!entriesResolved(run)) values=new Pnl(values.realizedGross(),null,null,null,null,false,values.openPositions());
        return new View(run,values,members.stream().map(TradeCostCalculator::cashCostView).toList(),requests.findByStrategyRunId(run.getId()).size(),averaging
                ?"PAPER_NET_INR; ₹100 roundtrip cost reserve per filled lot or higher recorded costs; not verified MCX brokerage"
                :"GROSS_INR; net includes estimated app charges, not broker-reconciled fees");
    }
    public View close(String id,Long userId,boolean admin) {
        var run=owned(id,userId,admin);
        runs.requestClose(id,"STRATEGY_MANUAL_CLOSE",now());
        reconcile(runs.findById(id).orElse(run));
        return view(runs.findById(id).orElseThrow());
    }
    public void closeAllAndPause(Long subscriptionId,Long userId,boolean admin) {
        subscriptions.cancel(subscriptionId,userId,admin); // Owner check and pause FIRST; an in-flight evaluator cannot create another run.
        for(var run:runs.findBySubscriptionIdAndStatusIn(subscriptionId,List.of("OPEN","CLOSING"))) {
            owned(run.getId(),userId,admin);runs.requestClose(run.getId(),"STRATEGY_CLOSE_ALL",now());
        }
    }
    private StrategyRun owned(String id,Long userId,boolean admin) {
        var run=runs.findById(id).orElseThrow(()->new IllegalArgumentException("Strategy instance not found"));
        if(!admin && !Objects.equals(run.getAppUserId(),userId)) throw new AccessDeniedException("Strategy instance does not belong to this account");
        return run;
    }
    private List<TriggeredTradeSetupEntity> members(StrategyRun run) {
        var members=trades.findByStrategyRunId(run.getId());
        for(var trade:members) if(!Objects.equals(run.getAppUserId(),trade.getAppUserId()) || !Objects.equals(run.getBrokerCredentialsId(),trade.getBrokerCredentialsId()))
            throw new IllegalStateException("Strategy instance has an inconsistent owner/broker mapping; reconciliation required");
        return members;
    }
    @Scheduled(fixedDelayString="${app.strategy.run-monitor-ms:1000}")
    public void monitor() {
        monitor(now());
    }
    void monitor(LocalDateTime time) {
        if(time.toLocalTime().isBefore(LocalTime.of(9,0)) || !time.toLocalTime().isBefore(LocalTime.of(23,30))) return;
        for(var run:runs.findByStatusIn(List.of("OPEN","CLOSING"))) {
            try {
                boolean naturalGas=org.com.sharekhan.strategy.mcx.NaturalGasSession.isTemplate(run.getTemplateId());
                if (naturalGas) {
                    if (!org.com.sharekhan.strategy.mcx.NaturalGasSession.marketOpen(time)) continue;
                    if (run.getTradingDay().isBefore(time.toLocalDate()) || !time.toLocalTime().isBefore(org.com.sharekhan.strategy.mcx.NaturalGasSession.SQUARE_OFF))
                        runs.requestClose(run.getId(),"MCX_SESSION_CLOSE",time);
                    else if (!time.toLocalTime().isBefore(org.com.sharekhan.strategy.mcx.NaturalGasSession.ENTRY_CUTOFF)) cancelPendingEntries(run);
                } else if(time.toLocalTime().isBefore(LocalTime.of(9,15)) || time.toLocalTime().isAfter(LocalTime.of(15,40))) continue;
                if("OPEN".equals(run.getStatus())) {
                    var members=members(run);var values=pnl(members,Instant.now());
                    if(riskReady(run,members) && values.marksFresh() && values.totalGross()!=null && entriesResolved(run)) {
                        var decision=risk.evaluate(run.getRiskSettings(),run.getLots(),values.totalGross(),run.getPeakPnl(),run.getStopFloor());
                        runs.advanceRisk(run.getId(),decision.peak(),decision.floor());
                        if(decision.closeReason()!=null) runs.requestClose(run.getId(),decision.closeReason(),time);
                    }
                    if(run.getTradingDay().isBefore(time.toLocalDate()) && !members.isEmpty() && members.stream().allMatch(t->flat(t) && !uncertain(t.getReason())) && entriesResolved(run))
                        runs.requestClose(run.getId(),"STRATEGY_COMPLETED",time);
                }
                var latest=runs.findById(run.getId()).orElseThrow();
                if("CLOSING".equals(latest.getStatus())) reconcile(latest);
            } catch(RuntimeException failure) {log.error("Strategy run {} remains under monitoring: {}",run.getId(),failure.getMessage());}
        }
    }
    private boolean riskReady(StrategyRun run,List<TriggeredTradeSetupEntity> members) {
        // Averaging owns paper-net basket targets, daily loss accounting and its monotone premium floor.
        if(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.TEMPLATE.equalsIgnoreCase(run.getTemplateId()))return false;
        if(members.isEmpty()) return false;
        if(!org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(run.getTemplateId())) return true;
        return members.size()==2 && members.stream().allMatch(t->t.getStatus()==TriggeredTradeStatus.EXECUTED)
                && Objects.equals(members.get(0).getQuantity(),members.get(1).getQuantity())
                && members.stream().map(TriggeredTradeSetupEntity::getOptionType).collect(java.util.stream.Collectors.toSet()).equals(Set.of("CE","PE"));
    }
    private boolean entriesResolved(StrategyRun run) {
        var records=requests.findByStrategyRunId(run.getId());
        for(var request:records) {
            if(!Objects.equals(request.getAppUserId(),run.getAppUserId()) || !Objects.equals(request.getBrokerCredentialsId(),run.getBrokerCredentialsId())) return false;
            if(uncertain(request.getReason())) return false;
            if(request.getStatus()==TriggeredTradeStatus.EXECUTED) {if(trades.findByTriggerRequestId(request.getId()).isEmpty()) return false;continue;}
            if(request.getStatus()!=null && FLAT.contains(request.getStatus())) continue;
            return false;
        }
        return true;
    }
    /** Cancel unsubmitted requests, reconcile broker entries, then close filled positions. */
    void reconcile(StrategyRun run) {
        cancelUnsubmittedRequests(run);
        for(var trade:members(run)) {
            try {
                if(trade.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION) execution.cancelStrategyRunEntry(trade.getId(),run.getId());
                else if(trade.getStatus()!=null && EXITABLE.contains(trade.getStatus())) execution.squareOffStrategyRunTrade(trade.getId(),run.getId(),run.getExitReason());
            } catch(RuntimeException failure) {log.warn("Run {} trade {} still needs exit/cancel reconciliation: {}",run.getId(),trade.getId(),failure.getMessage());}
        }
        // Read again: an HTTP acknowledgement, pending cancellation, unknown submission or late fill is not flat.
        if(entriesResolved(run) && members(run).stream().allMatch(t->flat(t) && !uncertain(t.getReason()))) runs.markClosed(run.getId(),now());
    }
    private void cancelUnsubmittedRequests(StrategyRun run) {
        for(var request:requests.findByStrategyRunId(run.getId())) {
            if(!Objects.equals(request.getAppUserId(),run.getAppUserId()) || !Objects.equals(request.getBrokerCredentialsId(),run.getBrokerCredentialsId())) throw new IllegalStateException("Request owner/broker mismatch");
            if(request.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION && !execution.isBrokerTriggerEntryInFlight(request.getId())
                    && trades.findByTriggerRequestId(request.getId()).isEmpty()) {
                requests.claimIfStatusEqualsWithOutcome(request.getId(),TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),TriggeredTradeStatus.CANCELLED.name(),"STRATEGY_RUN_CLOSED","Cancelled before broker submission by strategy instance close");
            }
        }
    }
    private void cancelPendingEntries(StrategyRun run) {
        cancelUnsubmittedRequests(run);
        for (var trade : members(run)) {
            if (trade.getStatus() == TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                execution.cancelStrategyRunEntry(trade.getId(), run.getId());
        }
    }
    public Pnl pnl(List<TriggeredTradeSetupEntity> members,Instant now) {
        BigDecimal realized=BigDecimal.ZERO,open=BigDecimal.ZERO,costs=BigDecimal.ZERO;
        boolean fresh=true,costsKnown=true;int count=0;List<Instant> quoteTimes=new ArrayList<>();
        for(var trade:members) {
            if(trade.getStatus()==TriggeredTradeStatus.REJECTED || trade.getStatus()==TriggeredTradeStatus.CANCELLED || trade.getStatus()==TriggeredTradeStatus.FAILED) {if(uncertain(trade.getReason())) fresh=false;continue;}
            if(trade.getQuantity()==null || trade.getQuantity()<=0 || trade.getActualEntryPrice()==null || !Double.isFinite(trade.getActualEntryPrice()) || trade.getActualEntryPrice()<=0) {fresh=false;costsKnown=false;continue;}
            boolean closed=trade.getStatus()==TriggeredTradeStatus.EXITED_SUCCESS || trade.getStatus()==TriggeredTradeStatus.COMPLETED;
            double price;
            if(closed) {
                if(trade.getExitPrice()==null || !Double.isFinite(trade.getExitPrice())) {fresh=false;costsKnown=false;continue;}
                price=trade.getExitPrice();
            } else {
                count++;
                if(trade.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION || trade.getStatus()==TriggeredTradeStatus.ENTRY_SUBMITTING || trade.getStatus()==TriggeredTradeStatus.TRIGGERED) {fresh=false;costsKnown=false;continue;}
                var quote=execution.strategyProfitExitQuote(trade,now);
                if(quote.isEmpty()) {fresh=false;costsKnown=false;continue;}
                quoteTimes.add(quote.get().getLastBookAt());price=sellSide(trade)?quote.get().getBestAsk():quote.get().getBestBid();
            }
            var value=BigDecimal.valueOf(price).subtract(BigDecimal.valueOf(trade.getActualEntryPrice())).multiply(BigDecimal.valueOf(trade.getQuantity())).multiply(BigDecimal.valueOf(sellSide(trade)?-1:1));
            if(closed) realized=realized.add(trade.getPnl()!=null && Double.isFinite(trade.getPnl())?BigDecimal.valueOf(trade.getPnl()):value);else open=open.add(value);
            if(sellSide(trade) && !TradeCostCalculator.isCashEquity(trade)) {costsKnown=false;continue;}
            if ("MX".equalsIgnoreCase(trade.getExchange())) {costsKnown=false;continue;} // Equity fee constants are not MCX charges.
            var estimate=TriggeredTradeSetupEntity.builder().actualEntryPrice(trade.getActualEntryPrice()).exitPrice(price)
                    .quantity(trade.getQuantity()).lots(trade.getLots()).originalLots(trade.getOriginalLots())
                    .targetOrderGroupId(trade.getTargetOrderGroupId()).exchange(trade.getExchange()).optionType(trade.getOptionType())
                    .entryAction(trade.getEntryAction()).source(trade.getSource()).intraday(trade.getIntraday()).pnl(value.doubleValue()).build();
            var charges=TradeCostCalculator.calculateCharges(estimate);
            if(charges==null) costsKnown=false;else costs=costs.add(BigDecimal.valueOf(closed && !TradeCostCalculator.isCashEquity(trade) && trade.getTradeCost()!=null && Double.isFinite(trade.getTradeCost())?trade.getTradeCost():charges.totalTradeCost()));
        }
        if(quoteTimes.size()>1 && Duration.between(Collections.min(quoteTimes),Collections.max(quoteTimes)).compareTo(Duration.ofSeconds(2))>0) fresh=false;
        var total=fresh?realized.add(open):null;
        return new Pnl(money(realized),fresh?money(open):null,total==null?null:money(total),costsKnown?money(costs):null,total!=null && costsKnown?money(total.subtract(costs)):null,fresh,count);
    }
    private static boolean sellSide(TriggeredTradeSetupEntity trade) {return org.com.sharekhan.util.TradeSide.isShort(trade);}
    private static boolean flat(TriggeredTradeSetupEntity trade) {return trade.getStatus()!=null && FLAT.contains(trade.getStatus());}
    private static boolean uncertain(String reason) {return reason!=null && (reason.contains("UNKNOWN") || reason.contains("UNCONFIRMED") || reason.contains("RECONCILIATION_REQUIRED"));}
    private static BigDecimal money(BigDecimal value) {return value.setScale(2,RoundingMode.HALF_UP);}
    private static LocalDateTime now() {return LocalDateTime.now(StrategySupport.MARKET_ZONE);}
}
