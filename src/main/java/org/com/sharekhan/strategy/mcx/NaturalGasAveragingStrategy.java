package org.com.sharekhan.strategy.mcx;

import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.*;
import org.springframework.stereotype.Component;

/** Independent paper basket: one bought option lot per distinct closed-bar decision. */
@Component @RequiredArgsConstructor @Slf4j
public class NaturalGasAveragingStrategy implements StrategyEvaluator {
    public static final String TEMPLATE="MCX_NATURALGAS_TREND_AVERAGING",SOURCE="strategy:"+TEMPLATE;
    private static final StrategyMetadata META=new StrategyMetadata(TEMPLATE,"NG Trend Averaging",
            "Simulator: ₹60,000 capital, up to four standard NG lots added one at a time. ATM CE/PE follows closed 15m EMA9/21 trend and 5m alignment; distinct pullbacks add the same contract. ₹1,250 basket paper-net target, ₹3,000 daily loss allowance; ₹100 roundtrip reserve per filled lot. Exit on trend reversal or from 23:20 IST. Maximum lots are not guaranteed affordable.","AUTO");
    private final ScriptMasterRepository scripts;
    private final BrokerCredentialsRepository brokers;
    private final TriggerTradeRequestRepository requests;
    private final TriggeredTradeSetupRepository setups;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    private final TradeExecutionService execution;
    private final StrategySupport support;
    private final StrategyRunCoordinator coordinator;
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r) {
        if(r.getSymbol()!=null && !r.getSymbol().isBlank() && !"NATURALGAS".equalsIgnoreCase(r.getSymbol().trim()))throw new IllegalArgumentException("NG Trend Averaging supports standard NATURALGAS only");
        r.setSymbol("NATURALGAS");r.setIntraday(true);
        if(r.getLots()==null)r.setLots(4);
        if(r.getLots()!=4)throw new IllegalArgumentException("Configure maximum four lots; each qualifying entry buys one lot");
        var risk=StrategyRiskSettings.validate(r.getRiskSettings(),4);
        if(risk==null)risk=new StrategyRiskSettings();
        if(risk.getAmountMode()!=null && risk.getAmountMode()!=StrategyRiskSettings.AmountMode.TOTAL
                || risk.getProfitTarget()!=null && risk.getProfitTarget().compareTo(BigDecimal.valueOf(1250))!=0
                || risk.getMaxLoss()!=null && risk.getMaxLoss().compareTo(BigDecimal.valueOf(3000))!=0
                || risk.getTrailActivation()!=null || risk.getSlStepProfit()!=null)throw new IllegalArgumentException("Averaging uses fixed total ₹1,250 basket target and ₹3,000 daily loss; no separate run trailing rules");
        risk.setAmountMode(StrategyRiskSettings.AmountMode.TOTAL);risk.setProfitTarget(new BigDecimal("1250.00"));risk.setMaxLoss(new BigDecimal("3000.00"));r.setRiskSettings(risk);
        if(r.getInvestmentPerStock()!=null)throw new IllegalArgumentException("Averaging capital is fixed at ₹60,000 per strategy account");
    }
    public void validateConfiguration(StrategyApplyRequest r) {
        validate(r);
        if(!brokers.findById(Objects.requireNonNullElse(r.getBrokerCredentialsId(),-1L))
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()) && "SIMULATOR".equalsIgnoreCase(b.getBrokerName())).isPresent())
            throw new IllegalArgumentException("NG Trend Averaging requires an owned Simulator account");
    }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest r){return apply(r,LocalDateTime.now(StrategySupport.MARKET_ZONE));}
    synchronized StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now) {
        validateConfiguration(r);
        if(!NaturalGasSession.entryAllowed(now))return waitFor("Outside NG entry window; no new averaging lots");
        String runId=coordinator.currentId();
        var previous=requests.findNaturalGasDailyRequests(SOURCE,r.getUserId(),r.getBrokerCredentialsId(),now.toLocalDate().atStartOfDay(),now.toLocalDate().plusDays(1).atStartOfDay());
        for(var p:previous) {
            if(!Objects.equals(p.getAppUserId(),r.getUserId()) || !Objects.equals(p.getBrokerCredentialsId(),r.getBrokerCredentialsId()))return waitFor("Request ownership mismatch; reconciliation required");
            if(p.getStatus()==null || Set.of(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,TriggeredTradeStatus.ENTRY_SUBMITTING,TriggeredTradeStatus.TRIGGERED,TriggeredTradeStatus.FAILED).contains(p.getStatus()) || NaturalGasAveragingBook.uncertain(p.getReason()))return waitFor("A prior entry is pending or uncertain; no additional lot");
        }
        var positions=setups.findBySource(SOURCE);
        for(var p:previous)if(p.getStatus()==TriggeredTradeStatus.EXECUTED && positions.stream().noneMatch(t->Objects.equals(t.getTriggerRequestId(),p.getId())))return waitFor("Executed request has no confirmed fill record; reconcile before adding");
        var rows=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS");
        var pair=NaturalGasContracts.resolve(rows,now.toLocalDate());var future=pair.future();
        if(future.getTickSize()==null || !Double.isFinite(future.getTickSize()) || future.getTickSize()<=0)return waitFor("Futures tick metadata unavailable");
        data.watch(future.getScripCode());execution.warmUpSpotLtp(future,"NG Trend Averaging futures");
        var signal=NaturalGasAveragingSignals.inspect(data.shoonyaCandles(future,now),now);
        String context="; "+signal.context();
        if(!signal.trendReady() || !signal.healthy() || !signal.alignedFive() || !signal.fresh(now))return waitFor("Waiting for healthy 15m trend, 5m alignment and a fresh completed bar"+context+"; "+data.shoonyaStatus(future.getScripCode()));
        // One action per completed bar, including rejected submissions; known pre-placement waits may retry.
        for(var p:previous)if(p.getCreatedAt()==null || p.getCreatedAt().isAfter(now))return waitFor("Entry request clock unavailable");
        var activity=previous.stream().filter(p->!org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON.equals(p.getReason()))
                .map(TriggerTradeRequestEntity::getCreatedAt).max(LocalDateTime::compareTo).orElse(null);
        if(activity!=null && !signal.signalEnd().isAfter(activity))return waitFor("Waiting for a new five-minute decision after the previous attempt"+context);
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        var fq=quotes.getSnapshot(future.getScripCode()).orElse(null);
        if(fq==null || fq.getLastTradedPrice()==null || !Double.isFinite(fq.getLastTradedPrice()) || fq.getLastTradedPrice()<=0
                || fq.getLastLtpAt()==null || fq.getLastLtpAt().isAfter(instant) || fq.getLastLtpAt().isBefore(instant.minusSeconds(15))
                || !"SHAREKHAN_WS".equals(fq.getLtpSource()) || !("scrip:"+future.getScripCode()).equals(fq.getLtpProviderIdentity()))return waitFor("Waiting for exact fresh NG futures quote"+context);
        var owned=positions.stream().filter(t->Objects.equals(t.getAppUserId(),r.getUserId()) && Objects.equals(t.getBrokerCredentialsId(),r.getBrokerCredentialsId())).toList();
        var held=owned.stream().filter(t->runId.equals(t.getStrategyRunId()) && t.getActualEntryPrice()!=null).findFirst().orElse(null);
        ScriptMasterEntity option;
        if(held==null)option=NaturalGasContracts.atm(rows,pair.optionExpiry(),signal.side(),fq.getLastTradedPrice());
        else {
            if(!signal.side().equals(held.getOptionType()))return waitFor("Held basket trend reversed; close it before switching side"+context);
            var exact=rows.stream().filter(t->Objects.equals(t.getScripCode(),held.getScripCode()) && Objects.equals(t.getOptionType(),held.getOptionType())
                    && Objects.equals(t.getExpiry(),held.getExpiry()) && Objects.equals(t.getStrikePrice(),held.getStrikePrice())).toList();
            if(exact.size()!=1)return waitFor("Held option contract no longer resolves uniquely; never switch strikes while averaging");
            option=exact.getFirst();
        }
        if(option.getLotSize()==null || option.getLotSize()!=1250 || !Objects.equals(option.getLotSize(),future.getLotSize()) || option.getTickSize()==null || !Double.isFinite(option.getTickSize()) || Math.abs(option.getTickSize()-NaturalGasAveragingBook.OPTION_TICK)>1e-9)return waitFor("Standard NG contract lot/tick metadata mismatch");
        var trigger=new TriggerRequest();trigger.setInstrument("NATURALGAS");trigger.setExchange("MX");trigger.setOptionType(option.getOptionType());
        trigger.setStrikePrice(option.getStrikePrice());trigger.setExpiry(option.getExpiry());trigger.setUserId(r.getUserId());trigger.setBrokerCredentialsId(r.getBrokerCredentialsId());
        trigger.setSource(SOURCE);trigger.setAction("BUY");trigger.setQuantity(1);trigger.setLots(1);trigger.setIntraday(true);trigger.setTslEnabled(false);
        trigger.setUseSpotPrice(false);trigger.setUseSpotForEntry(false);trigger.setUseSpotForSl(false);trigger.setUseSpotForTarget(false);
        execution.warmUpOptionLtp(trigger,"NG Trend Averaging same-contract basket");
        var q=quotes.getSnapshot(option.getScripCode()).orElse(null);
        if(!usable(q,instant,option.getScripCode()))return waitFor("Waiting for exact option bid/ask no older than five seconds with spread at most 2%");
        double ask=NaturalGasAveragingBook.ceil(q.getBestAsk(),option.getTickSize());
        var state=NaturalGasAveragingBook.inspect(owned,r.getUserId(),r.getBrokerCredentialsId(),now,marks(owned,quotes,instant),option.getTickSize());
        var basket=state.current(runId);
        if(state.lastExit()!=null && !signal.signalEnd().isAfter(state.lastExit()))return waitFor("Waiting for a new signal after the last basket exit");
        String denied=NaturalGasAveragingBook.admit(state,runId,ask,q.getBestBid(),option.getLotSize(),option.getTickSize());
        if(denied!=null)return waitFor(denied+context);
        if(basket!=null) {
            if(!signal.pullback() || signal.atr()<=0)return waitFor("Waiting for a five-minute pullback to EMA9 while trend stays aligned"+context);
            var anchor=signal.previousFillClose(basket.lastEntry());
            if(anchor.isEmpty())return waitFor("Previous fill's completed futures bar unavailable; do not invent an averaging level");
            double step=Math.max(future.getTickSize(),.5*signal.atr());
            boolean distinct="CE".equals(signal.side())?signal.close()<=anchor.getAsDouble()-step+1e-8:signal.close()>=anchor.getAsDouble()+step-1e-8;
            var last=basket.legs().getLast();
            if(!distinct || ask>basket.invested()/basket.quantity()-option.getTickSize()+1e-8 || ask>last.getActualEntryPrice()-option.getTickSize()+1e-8)return waitFor("Pullback has not reached a new cheaper option level and half-ATR futures step"+context);
        }
        log.info("MCX_AVERAGING_ENTRY | subscription={} run={} side={} nextLot={} ask={} capitalUsed={} dailyUsed={} context={}",
                r.getSubscriptionId(),runId,signal.side(),basket==null?1:basket.lots()+1,ask,basket==null?0:basket.invested(),state.dailyUsed(),signal.context());
        trigger.setEntryPrice(ask); // No individual targets/stops: the basket monitor owns all exits.
        var result=support.executeTriggeredTrade(trigger);
        return StrategyApplyResponse.builder().templateId(TEMPLATE).symbol("NATURALGAS").direction(signal.side()).status("triggered")
                .message("One NG "+signal.side()+" lot requested for the averaging basket; max four lots/₹60,000 capital. Basket target ₹1,250 after paper cost reserves; daily allowance ₹3,000"+context)
                .triggerRequest(trigger).tradeRequest(result).build();
    }
    static boolean usable(QuoteCacheService.QuoteSnapshot q,Instant now,Integer code) {
        return exitUsable(q,now,code) && (q.getBestAsk()-q.getBestBid())/q.getBestAsk()<=.02;
    }
    static boolean exitUsable(QuoteCacheService.QuoteSnapshot q,Instant now,Integer code) {
        return q!=null && q.hasBook() && q.isCoherentBook() && Double.isFinite(q.getBestBid()) && Double.isFinite(q.getBestAsk())
                && q.getBestBid()>0 && q.getBestBid()<=q.getBestAsk() && q.getLastBookAt()!=null && !q.getLastBookAt().isAfter(now)
                && !q.getLastBookAt().isBefore(now.minusSeconds(5)) && "SHAREKHAN_WS".equals(q.getBookSource())
                && ("scrip:"+code).equals(q.getBookProviderIdentity());
    }
    static Map<Integer,Double> marks(List<TriggeredTradeSetupEntity> positions,QuoteCacheService quotes,Instant now) {
        var result=new HashMap<Integer,Double>();
        for(var t:positions)if(t.getScripCode()!=null && !NaturalGasAveragingBook.closed(t)) {
            var q=quotes.getSnapshot(t.getScripCode()).orElse(null);if(exitUsable(q,now,t.getScripCode()))result.put(t.getScripCode(),q.getBestBid());
        }
        return result;
    }
    private StrategyApplyResponse waitFor(String reason){return support.waiting(META,"NATURALGAS",reason);}
}
