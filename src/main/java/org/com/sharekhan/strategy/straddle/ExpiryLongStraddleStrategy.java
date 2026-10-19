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
                "Buy ATM CE + PE on listed expiry days at 09:30 and 13:30 IST; close both at 10:30 and 14:30. Symbol defaults to NIFTY; lots per leg. No stop or profit target.","CE+PE");
    }
    public static boolean owns(String source) { return source!=null && source.startsWith(SOURCE_PREFIX); }
    public static String source(ExpiryStraddleSession session) { return SOURCE_PREFIX+session.getId(); }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest request) {
        String symbol=ExpiryStraddleRules.symbol(request.getSymbol());
        int lots=ExpiryStraddleRules.lots(request.getLots());
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
                .slot(slot.get().id()).exitAt(now.toLocalDate().atTime(slot.get().exit())).strike(strike).lots(lots).claimedAt(now).build();
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
        sessions.save(session);
        manageSession(session,LocalDateTime.now(clock));
        return StrategyApplyResponse.builder().status(session.isAbortRequested()?"error":"triggered")
                .message(session.getMessage()).templateId(metadata().id()).symbol(symbol).direction("CE+PE").tradeRequest(first).build();
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
        var legs=trades.findBySource(source(session));
        boolean entryGraceOver=!now.isBefore(session.getClaimedAt().withSecond(0).withNano(0).plusMinutes(1));
        boolean balanced=legs.size()==2 && legs.stream().allMatch(t->t.getStatus()==TriggeredTradeStatus.EXECUTED)
                && Objects.equals(legs.get(0).getQuantity(),legs.get(1).getQuantity())
                && Objects.equals(legs.get(0).getQuantity(),session.getExpectedQuantity())
                && legs.stream().map(TriggeredTradeSetupEntity::getOptionType).collect(java.util.stream.Collectors.toSet()).equals(Set.of("CE","PE"));
        if(entryGraceOver && !balanced && now.isBefore(session.getExitAt()) && !legs.stream().allMatch(t->terminal(t.getStatus()))) {
            session.setAbortRequested(true);session.setMessage("Unbalanced/unconfirmed pair; unwinding exposure");sessions.save(session);
        }
        if(!session.isAbortRequested() && now.isBefore(session.getExitAt())) return;
        for(var trade:legs) {
            try {
                if(trade.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                    execution.cancelTimedStrategyEntry(trade.getId());
                else if(Set.of(TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.TARGET_ORDER_PLACED,
                        TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED).contains(trade.getStatus()))
                    execution.squareOffTimedStrategyTrade(trade.getId());
            } catch(RuntimeException failure) { log.error("Straddle exit/cancel pending for session {} trade {}",session.getId(),trade.getId(),failure); }
        }
    }
    private static boolean terminal(TriggeredTradeStatus status) {
        return status!=null && Set.of(TriggeredTradeStatus.REJECTED,TriggeredTradeStatus.FAILED,TriggeredTradeStatus.CANCELLED,
                TriggeredTradeStatus.COMPLETED,TriggeredTradeStatus.EXITED_SUCCESS).contains(status);
    }
}
