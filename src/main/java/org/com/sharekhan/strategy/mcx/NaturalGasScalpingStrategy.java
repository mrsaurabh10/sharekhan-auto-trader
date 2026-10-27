package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.cache.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.*;

@Component @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class NaturalGasScalpingStrategy implements StrategyEvaluator {
    private final ScriptMasterRepository scripts;
    private final BrokerCredentialsRepository brokers;
    private final TriggerTradeRequestRepository requests;
    private final TriggeredTradeSetupRepository setups;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    private final TradeExecutionService execution;
    private final StrategySupport support;
    public static final String TEMPLATE="MCX_NATURALGAS_SCALPING";
    public static final String SOURCE="strategy:"+TEMPLATE;
    private static final StrategyMetadata META=new StrategyMetadata(TEMPLATE,"Scalping",
            "Simulator: MCX ATM CE/PE, three-minute direct breakout with five-minute trend, 10% premium stop / 15% target. One lot, five attempts, six-minute cooldown; total daily loss allowance at most ₹2,500 including paper cost reserves. Entries before 23:00; square-off from 23:20 IST.","AUTO");
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r) {
        if(r.getSymbol()!=null && !r.getSymbol().isBlank() && !"NATURALGAS".equalsIgnoreCase(r.getSymbol().trim())) throw new IllegalArgumentException("This strategy supports MCX NATURALGAS only");
        r.setSymbol("NATURALGAS");r.setIntraday(true);
        if(r.getLots()==null)r.setLots(1);
        if(r.getLots()!=1)throw new IllegalArgumentException("Scalping Simulator trial supports one lot only");
        r.setRiskSettings(org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(r.getRiskSettings(),r.getLots()));
        if(r.getRiskSettings()==null || r.getRiskSettings().getMaxLoss()==null) throw new IllegalArgumentException("Set a positive rupee maximum loss for the Natural Gas strategy run");
        r.getRiskSettings().setAmountMode(org.com.sharekhan.strategy.run.StrategyRiskSettings.AmountMode.TOTAL);
        if(r.getRiskSettings().scale(r.getRiskSettings().getMaxLoss(),r.getLots()).compareTo(NaturalGasScalpPolicy.DAILY_LIMIT)>0)throw new IllegalArgumentException("Scalping total daily loss limit must not exceed ₹2,500");
    }
    public void validateConfiguration(StrategyApplyRequest r) {
        validate(r);
        var broker=brokers.findById(r.getBrokerCredentialsId()==null?-1:r.getBrokerCredentialsId())
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()))
                .orElseThrow(()->new IllegalArgumentException("Choose an owned broker account"));
        if(!"SIMULATOR".equalsIgnoreCase(broker.getBrokerName()))
            throw new IllegalArgumentException("Scalping currently supports Simulator only");
    }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest request){return apply(request,LocalDateTime.now(StrategySupport.MARKET_ZONE));}
    public synchronized StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now) {
        validateConfiguration(r);
        if(!NaturalGasSession.entryAllowed(now))return waitFor("Outside MCX entry window 09:00-23:00 IST");
        var brokerName=brokers.findById(r.getBrokerCredentialsId()).orElseThrow().getBrokerName();
        var unresolvedStatuses=List.of(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
                org.com.sharekhan.enums.TriggeredTradeStatus.ENTRY_SUBMITTING, org.com.sharekhan.enums.TriggeredTradeStatus.TRIGGERED,
                org.com.sharekhan.enums.TriggeredTradeStatus.FAILED);
        boolean unresolvedRequest=requests.findBySourceIgnoreCaseAndStatusIn(SOURCE,unresolvedStatuses).stream()
                .filter(p->Objects.equals(p.getAppUserId(),r.getUserId()) && Objects.equals(p.getBrokerCredentialsId(),r.getBrokerCredentialsId()))
                .anyMatch(p->p.getStatus()!=org.com.sharekhan.enums.TriggeredTradeStatus.FAILED || uncertain(p.getReason()));
        var positions=setups.findBySource(SOURCE);
        boolean openPosition=positions.stream()
                .filter(p->Objects.equals(p.getAppUserId(),r.getUserId()) && Objects.equals(p.getBrokerCredentialsId(),r.getBrokerCredentialsId()))
                .anyMatch(p->p.getStatus()==null || !Set.of(org.com.sharekhan.enums.TriggeredTradeStatus.EXITED_SUCCESS,
                        org.com.sharekhan.enums.TriggeredTradeStatus.COMPLETED, org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED,
                        org.com.sharekhan.enums.TriggeredTradeStatus.CANCELLED, org.com.sharekhan.enums.TriggeredTradeStatus.FAILED).contains(p.getStatus()) || uncertain(p.getReason()));
        if(unresolvedRequest || openPosition) return waitFor("An existing Natural Gas position or uncertain entry must be reconciled before another entry");
        var prior=requests.findNaturalGasDailyRequests(SOURCE,r.getUserId(),r.getBrokerCredentialsId(),now.toLocalDate().atStartOfDay(),now.toLocalDate().plusDays(1).atStartOfDay());
        NaturalGasScalpDay day=NaturalGasScalpDay.inspect(r,prior,positions,now);
        if(day.problem()!=null)return waitFor(day.problem()+day.context());
        var rows=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS");
        var pair=NaturalGasContracts.resolve(rows,now.toLocalDate());var future=pair.future();
        if(future.getTickSize()==null || !Double.isFinite(future.getTickSize()) || future.getTickSize()<=0) return waitFor("Futures tick metadata unavailable");
        data.watch(future.getScripCode());
        execution.warmUpSpotLtp(future,"MCX Natural Gas strategy");
        var source=NaturalGasCandleSource.SHOONYA_TPS;
        var candles=data.shoonyaCandles(future,now);
        var evaluation=NaturalGasScalpSignals.inspect(data.threeMinuteCandles(future,now),candles,now,future.getTickSize());
        String dataContext=(evaluation.dataContext().isBlank()?"":"; "+evaluation.dataContext())+"; candleSource="+source
                +(source==NaturalGasCandleSource.SHOONYA_TPS?" (prior seed=Sharekhan 5m; today=Shoonya TPSeries)":"")
                +day.context();
        log.info("MCX_DATA_EVALUATION | subscription={} broker={} reason={} context={}",r.getSubscriptionId(),brokerName,evaluation.reason(),dataContext);
        if(evaluation.signal().isEmpty()) {
            String reason=evaluation.reason();
            if(reason.startsWith("No MCX candles") || reason.startsWith("No completed MCX") || reason.startsWith("Incomplete MCX") || reason.startsWith("Gap in MCX") || reason.startsWith("Stale MCX"))
                reason += "; "+(source==NaturalGasCandleSource.SHOONYA_TPS?data.shoonyaStatus(future.getScripCode()):data.status(future.getScripCode()));
            reason+="; "+data.threeMinuteStatus(future.getScripCode());
            return waitFor(reason+dataContext);
        }
        var signal=evaluation.signal().get();
        if(day.lastActivity()!=null && !signal.candleEnd().isAfter(day.lastActivity()))return waitFor("Scalp requires a new completed signal after the previous attempt/exit"+dataContext);
        var futureQuote=quotes.getSnapshot(future.getScripCode()).orElse(null);
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        if (futureQuote==null || futureQuote.getLastTradedPrice()==null || !Double.isFinite(futureQuote.getLastTradedPrice())
                || futureQuote.getLastTradedPrice()<=0 || futureQuote.getLastLtpAt()==null || futureQuote.getLastLtpAt().isAfter(instant)
                || futureQuote.getLastLtpAt().isBefore(instant.minusSeconds(15)) || !"SHAREKHAN_WS".equals(futureQuote.getLtpSource())
                || !("scrip:"+future.getScripCode()).equals(futureQuote.getLtpProviderIdentity()))
            return waitFor("Waiting for fresh exact-contract Sharekhan MCX futures price"+dataContext);
        double price=futureQuote.getLastTradedPrice();
        boolean ce="CE".equals(signal.side());
        if(!(ce?price>=signal.trigger():price<=signal.trigger()) || Math.abs(price-signal.trigger())>signal.atr())return waitFor("Waiting for momentum boundary without chasing more than one ATR"+dataContext);
        var option=NaturalGasContracts.atm(rows,pair.optionExpiry(),signal.side(),price);
        TriggerRequest t=new TriggerRequest();t.setInstrument("NATURALGAS");t.setExchange("MX");t.setOptionType(signal.side());
        t.setExpiry(option.getExpiry());t.setStrikePrice(option.getStrikePrice());t.setUserId(r.getUserId());t.setBrokerCredentialsId(r.getBrokerCredentialsId());
        t.setSource(SOURCE);t.setAction("BUY");t.setLots(r.getLots());t.setQuantity(r.getLots());t.setIntraday(true);
        execution.warmUpOptionLtp(t,"MCX Natural Gas exact ATM option");
        var q=quotes.getSnapshot(option.getScripCode()).orElse(null);
        if(!usableBook(q,instant,option.getScripCode()))return waitFor("Waiting for fresh coherent ATM option bid/ask (maximum 2% spread)"+dataContext);
        double premium=round(q.getBestAsk(),option.getTickSize(),RoundingMode.CEILING);
        double stop=round(premium*(1-NaturalGasScalpPolicy.STOP_FRACTION),option.getTickSize(),RoundingMode.FLOOR);
        if(stop<=0 || stop>=premium)return waitFor("Option premium too small for a valid tick-aligned stop"+dataContext);
        double estimatedRisk=(premium-stop)*option.getLotSize()*r.getLots();
        var budget=day.remaining();
        if(BigDecimal.valueOf(estimatedRisk+NaturalGasScalpPolicy.COST_RESERVE).compareTo(budget)>0)
            return waitFor("Scalp stop risk plus paper cost reserve exceeds the remaining daily allowance"+dataContext);
        double target=round(premium*(1+NaturalGasScalpPolicy.TARGET_FRACTION),option.getTickSize(),RoundingMode.CEILING);
        if((target-premium)*option.getLotSize()*r.getLots()<2*((q.getBestAsk()-q.getBestBid())*option.getLotSize()*r.getLots()+NaturalGasScalpPolicy.COST_RESERVE))
            return waitFor("Scalp target too small relative to displayed spread and paper cost reserve"+dataContext);
        t.setEntryPrice(premium);t.setStopLoss(stop);t.setTarget1(target);
        t.setUseSpotPrice(false);t.setUseSpotForEntry(false);t.setUseSpotForSl(false);t.setUseSpotForTarget(false);t.setTslEnabled(false);
        var trade=support.executeTriggeredTrade(t);
        return StrategyApplyResponse.builder().templateId(META.id()).symbol("NATURALGAS").direction(signal.side()).status("triggered")
                .message("MCX ATM "+signal.side()+" entry requested; "+"10% premium stop/15% target, tick-rounded; remaining daily allowance and paper costs apply. "+"Simulator assumes full top-of-book quantity; depth is unverified."+dataContext)
                .triggerRequest(t).tradeRequest(trade).breakoutClose(signal.trigger()).build();
    }
    static boolean usableBook(QuoteCacheService.QuoteSnapshot q,Instant now,Integer code){
        return q!=null && q.hasBook() && q.isCoherentBook() && Double.isFinite(q.getBestBid()) && Double.isFinite(q.getBestAsk())
                && q.getBestBid()<=q.getBestAsk() && q.getLastBookAt()!=null && !q.getLastBookAt().isAfter(now)
                && !q.getLastBookAt().isBefore(now.minusSeconds(15)) && "SHAREKHAN_WS".equals(q.getBookSource())
                && ("scrip:"+code).equals(q.getBookProviderIdentity()) && (q.getBestAsk()-q.getBestBid())/q.getBestAsk()<=.02;
    }
    private static boolean uncertain(String reason) {
        return reason!=null && (reason.contains("UNKNOWN") || reason.contains("UNCONFIRMED") || reason.contains("RECONCILIATION_REQUIRED"));
    }
    private StrategyApplyResponse waitFor(String message){return support.waiting(META,"NATURALGAS",message);}
    private static double round(double value,double tick,RoundingMode mode){return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(tick),0,mode).multiply(BigDecimal.valueOf(tick)).doubleValue();}
}
