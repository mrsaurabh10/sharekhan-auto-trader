package org.com.sharekhan.strategy.straddle;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.cache.LtpCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j @Component
public class ExpiryLongStraddleStrategy implements StrategyEvaluator {
    public static final String SOURCE_PREFIX="expiry-straddle:";
    private final StrategySupport support;
    private final ScriptMasterRepository scripts;
    private final ExpiryStraddleSessionRepository sessions;
    private final ExpiryStraddleSessionStore store;
    private final TriggeredTradeSetupRepository trades;
    private final TradeExecutionService execution;
    private final LtpCacheService prices;
    private final NseMarketCalendar calendar;
    @Autowired(required=false) private org.com.sharekhan.strategy.run.StrategyRunCoordinator runCoordinator;
    private final Clock clock;
    @Autowired
    public ExpiryLongStraddleStrategy(StrategySupport support,ScriptMasterRepository scripts,
            ExpiryStraddleSessionRepository sessions,ExpiryStraddleSessionStore store,
            TriggeredTradeSetupRepository trades,TradeExecutionService execution,
            LtpCacheService prices,NseMarketCalendar calendar) {
        this(support,scripts,sessions,store,trades,execution,prices,calendar,Clock.system(StrategySupport.MARKET_ZONE));
    }
    ExpiryLongStraddleStrategy(StrategySupport support,ScriptMasterRepository scripts,
            ExpiryStraddleSessionRepository sessions,ExpiryStraddleSessionStore store,
            TriggeredTradeSetupRepository trades,TradeExecutionService execution,
            LtpCacheService prices,NseMarketCalendar calendar,Clock clock) {
        this.support=support;this.scripts=scripts;this.sessions=sessions;this.store=store;
        this.trades=trades;this.execution=execution;this.prices=prices;this.calendar=calendar;this.clock=clock;
    }
    @Override public StrategyMetadata metadata() {
        return new StrategyMetadata(ExpiryStraddleRules.TEMPLATE_ID,"Expiry day long straddle",
                "Buy ATM CE + PE on listed expiry days at 09:30 and 13:30 IST; close both at 10:30 and 14:30. Symbol defaults to NIFTY; lots per leg. Optional combined profit target in rupees per lot per session; no stop.","CE+PE");
    }
    public static boolean owns(String source) { return source!=null && source.startsWith(SOURCE_PREFIX); }
    public static String source(ExpiryStraddleSession session) { return SOURCE_PREFIX+session.getId(); }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest request) {
        String symbol=ExpiryStraddleRules.symbol(request.getSymbol());
        int lots=ExpiryStraddleRules.lots(request.getLots());
        var profitTarget=ExpiryStraddleRules.profitTarget(request.getProfitTargetPerLot());
        if(request.getUserId()==null || request.getBrokerCredentialsId()==null)
            throw new IllegalArgumentException("Select an account and broker for the straddle");
        LocalDateTime now=LocalDateTime.now(clock);
        if(!calendar.isTradingDay(now.toLocalDate())) return support.waiting(metadata(),symbol,"Not a trading day");
        var slot=ExpiryStraddleRules.SESSIONS.stream().filter(s->s.entryWindow(now.toLocalTime())).findFirst();
        if(slot.isEmpty()) return support.waiting(metadata(),symbol,"Waiting for expiry-day 09:30 or 13:30 IST; missed entries are skipped");
        String expiry=now.toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        // Actual listed contracts handle changed expiry weekdays and exchange holidays.
        Set<Double> common=new HashSet<>(scripts.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(symbol,"CE",expiry));
        common.retainAll(scripts.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(symbol,"PE",expiry));
        if(common.isEmpty()) return support.waiting(metadata(),symbol,"No paired contracts expiring today");
        String id=now.toLocalDate()+":"+request.getUserId()+":"+request.getBrokerCredentialsId()+":"+symbol+":"+slot.get().id();
        if(sessions.existsById(id)) return support.waiting(metadata(),symbol,"This expiry session has already been attempted");
        // Do not enter PM while an earlier session still has pending entry/exit exposure.
        if(sessions.findByTradingDayLessThanEqual(now.toLocalDate()).stream()
                .filter(s->Objects.equals(s.getAppUserId(),request.getUserId()) && Objects.equals(s.getBrokerCredentialsId(),request.getBrokerCredentialsId()) && symbol.equals(s.getSymbol()))
                .anyMatch(s->{ var prior=trades.findBySource(source(s)); return prior.size()!=2 || prior.stream().anyMatch(t->!terminal(t.getStatus())); }))
            return support.waiting(metadata(),symbol,"Earlier straddle exposure is unresolved; new session blocked");
        var spot=support.resolveSpotScript(symbol);
        support.warmUpSpotFeed(spot);
        Double spotPrice=prices.getLtp(spot.getScripCode());
        LocalDateTime observed=prices.getObservedAt(spot.getScripCode());
        if(spotPrice==null || observed==null || observed.isBefore(now.minusSeconds(30)) || observed.isAfter(now))
            return support.waiting(metadata(),symbol,"Waiting for fresh spot price for ATM selection");
        double strike=ExpiryStraddleRules.atm(common,spotPrice);
        var session=ExpiryStraddleSession.builder().id(id).appUserId(request.getUserId())
                .brokerCredentialsId(request.getBrokerCredentialsId()).symbol(symbol).tradingDay(now.toLocalDate())
                .slot(slot.get().id()).exitAt(now.toLocalDate().atTime(slot.get().exit())).strike(strike).lots(lots).profitTargetPerLot(profitTarget).claimedAt(now).build();
        List<TriggerRequest> legs=new ArrayList<>();
        Integer lotSize=null;
        for(String side:List.of("CE","PE")) {
            var contract=scripts.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(symbol,strike,side,expiry).orElseThrow();
            if(contract.getLotSize()==null || contract.getLotSize()<1 || (lotSize!=null && !lotSize.equals(contract.getLotSize())))
                throw new IllegalArgumentException("Both contracts must have matching valid lot sizes");
            lotSize=contract.getLotSize();
            TriggerRequest leg=new TriggerRequest();
            leg.setInstrument(symbol);leg.setExchange(contract.getExchange());leg.setExpiry(expiry);leg.setStrikePrice(strike);leg.setOptionType(side);
            leg.setQuantity(lots);leg.setLots(lots);leg.setUserId(request.getUserId());leg.setBrokerCredentialsId(request.getBrokerCredentialsId());
            leg.setIntraday(true);leg.setTslEnabled(false);leg.setSource(source(session));leg.setAction("BUY");
            // Resolve both premiums BEFORE attempting either leg; no custom stop/target settings.
            leg.setEntryPrice(execution.strategyEntryReferencePrice(contract.getScripCode()));
            if(leg.getEntryPrice()==null || !Double.isFinite(leg.getEntryPrice()) || leg.getEntryPrice()<=0)
                return support.waiting(metadata(),symbol,"Both option premiums are required before entering");
            legs.add(leg);
        }
        session.setExpectedQuantity((long)lots * lotSize);
        if(!slot.get().entryWindow(LocalDateTime.now(clock).toLocalTime()) || !now.toLocalDate().equals(LocalDate.now(clock)))
            return support.waiting(metadata(),symbol,"Entry minute elapsed during quote preparation; session skipped");
        if(runCoordinator!=null) session.setStrategyRunId(runCoordinator.ensureCurrentRun());
        if(!store.claim(session)) return support.waiting(metadata(),symbol,"This expiry session is already claimed");
        TriggerTradeRequestEntity first=null;
        try {
            for(var leg:legs) {
                if(!slot.get().entryWindow(LocalTime.now(clock))) throw new IllegalStateException("Entry minute elapsed between legs");
                var placed=execution.executeTriggeredTrade(leg);
                if(first==null) first=placed;
                if(placed==null || placed.getStatus()==TriggeredTradeStatus.REJECTED || placed.getStatus()==TriggeredTradeStatus.FAILED)
                    throw new IllegalStateException("A straddle leg was rejected");
            }
            session.setMessage("Both leg entries submitted; fills must be confirmed");
        } catch(RuntimeException failure) {
            session.setAbortRequested(true);session.setMessage("Entry incomplete; close fills and cancel unfilled entries");
            log.error("Straddle session {} entry incomplete; recovery will unwind exposure",id,failure);
        }
        sessions.recordOutcome(session.getId(),session.isAbortRequested(),session.getMessage());
        sessions.findById(session.getId()).ifPresent(saved -> session.setProfitExitRequested(saved.isProfitExitRequested()));
        manageSession(session,LocalDateTime.now(clock));
        return StrategyApplyResponse.builder().status(session.isAbortRequested()?"error":"triggered")
                .message(session.getMessage()).templateId(metadata().id()).symbol(symbol).direction("CE+PE").tradeRequest(first).build();
    }
    /** Cache-only checks; the persisted exit decision survives restarts and price reversals. */
    @Scheduled(fixedDelayString="${app.strategy.straddle-profit-poll-ms:1000}")
    public synchronized void monitorProfitTargets() {
        LocalDateTime now=LocalDateTime.now(clock);
        if(now.toLocalTime().isBefore(LocalTime.of(9,15)) || now.toLocalTime().isAfter(LocalTime.of(15,30))) return;
        for(var session:sessions.findByProfitTargetPerLotIsNotNullAndTradingDayLessThanEqual(now.toLocalDate())) {
            try { manageSession(session,now); }
            catch(RuntimeException failure) { log.error("Straddle profit monitoring failed for {}",session.getId(),failure); }
        }
    }

    /** Independent of subscription status/readiness: cancellation must not disable existing exits. */
    @Scheduled(cron="0 * * * * MON-FRI",zone="Asia/Kolkata")
    public synchronized void manageExits() {
        LocalDateTime now=LocalDateTime.now(clock);
        if(now.toLocalTime().isBefore(LocalTime.of(9,15)) || now.toLocalTime().isAfter(LocalTime.of(15,30))) return;
        for(var session:sessions.findByTradingDayLessThanEqual(now.toLocalDate())) {
            try { manageSession(session,now); }
            catch(RuntimeException failure) { log.error("Straddle recovery failed for {}",session.getId(),failure); }
        }
    }
    void manageSession(ExpiryStraddleSession session,LocalDateTime now) {
        if(session.getStrategyRunId()!=null && runCoordinator!=null && !runCoordinator.isOpen(session.getStrategyRunId())) return;
        var legs=trades.findBySource(source(session));
        boolean entryGraceOver=!now.isBefore(session.getClaimedAt().withSecond(0).withNano(0).plusMinutes(1));
        boolean balanced=legs.size()==2 && legs.stream().allMatch(t->t.getStatus()==TriggeredTradeStatus.EXECUTED)
                && Objects.equals(legs.get(0).getQuantity(),legs.get(1).getQuantity())
                && Objects.equals(legs.get(0).getQuantity(),session.getExpectedQuantity())
                && legs.stream().map(TriggeredTradeSetupEntity::getOptionType).collect(java.util.stream.Collectors.toSet()).equals(Set.of("CE","PE"));
        if(balanced && !session.isAbortRequested() && !session.isProfitExitRequested()
                && session.getStrategyRunId()==null && now.isBefore(session.getExitAt()) && session.getProfitTargetPerLot()!=null) {
            var combined=combinedBidProfit(legs,now.atZone(StrategySupport.MARKET_ZONE).toInstant());
            var target=session.getProfitTargetPerLot().multiply(java.math.BigDecimal.valueOf(session.getLots()));
            if(combined.isPresent() && combined.get().compareTo(target)>=0) {
                String message="Combined gross profit target reached: INR "+target.toPlainString()+"; closing both legs";
                // Persist the group decision before placing either sell, even when the second leg fails.
                if(sessions.claimProfitExit(session.getId(),message)==1) {
                    session.setProfitExitRequested(true);session.setMessage(message);
                } else if(sessions.findById(session.getId()).map(ExpiryStraddleSession::isProfitExitRequested).orElse(false)) {
                    session.setProfitExitRequested(true);
                }
            }
        }
        if(entryGraceOver && !session.isProfitExitRequested() && !balanced && now.isBefore(session.getExitAt()) && !legs.stream().allMatch(t->terminal(t.getStatus()))) {
            session.setAbortRequested(true);session.setMessage("Unbalanced/unconfirmed pair; unwinding exposure");
            sessions.recordOutcome(session.getId(),true,session.getMessage());
            sessions.findById(session.getId()).ifPresent(saved -> session.setProfitExitRequested(saved.isProfitExitRequested()));
        }
        if(!session.isAbortRequested() && !session.isProfitExitRequested() && now.isBefore(session.getExitAt())) return;
        if(session.getStrategyRunId()!=null && runCoordinator!=null) {
            runCoordinator.requestClose(session.getStrategyRunId(),session.isAbortRequested()?"STRATEGY_ENTRY_ABORT":"STRATEGY_TIMED_EXIT");return;
        }
        for(var trade:legs) {
            try {
                if(trade.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                    execution.cancelTimedStrategyEntry(trade.getId());
                else if(Set.of(TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.TARGET_ORDER_PLACED,
                        TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED).contains(trade.getStatus())) {
                    if(session.isProfitExitRequested()) execution.squareOffProfitStrategyTrade(trade.getId());
                    else execution.squareOffTimedStrategyTrade(trade.getId());
                }
            } catch(RuntimeException failure) { log.error("Straddle exit/cancel pending for session {} trade {}",session.getId(),trade.getId(),failure); }
        }
    }
    private Optional<java.math.BigDecimal> combinedBidProfit(List<TriggeredTradeSetupEntity> legs,Instant now) {
        java.math.BigDecimal profit=java.math.BigDecimal.ZERO;
        List<Instant> timestamps=new ArrayList<>();
        for(var trade:legs) {
            // Requested entry prices cannot substitute for confirmed fills.
            if(trade.getActualEntryPrice()==null || !Double.isFinite(trade.getActualEntryPrice()) || trade.getActualEntryPrice()<=0) return Optional.empty();
            var quote=execution.strategyProfitExitQuote(trade,now);
            if(quote.isEmpty()) return Optional.empty();
            timestamps.add(quote.get().getLastBookAt());
            profit=profit.add(java.math.BigDecimal.valueOf(quote.get().getBestBid())
                    .subtract(java.math.BigDecimal.valueOf(trade.getActualEntryPrice()))
                    .multiply(java.math.BigDecimal.valueOf(trade.getQuantity())));
        }
        if(Duration.between(Collections.min(timestamps),Collections.max(timestamps)).compareTo(Duration.ofSeconds(2))>0) return Optional.empty();
        return Optional.of(profit);
    }

    private static boolean terminal(TriggeredTradeStatus status) {
        return status!=null && Set.of(TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.FAILED,TriggeredTradeStatus.CANCELLED,
                TriggeredTradeStatus.COMPLETED,TriggeredTradeStatus.EXITED_SUCCESS).contains(status);
    }
}
