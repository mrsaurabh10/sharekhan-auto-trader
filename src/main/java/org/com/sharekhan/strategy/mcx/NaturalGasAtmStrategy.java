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
public class NaturalGasAtmStrategy implements StrategyEvaluator {
    private final ScriptMasterRepository scripts;
    private final BrokerCredentialsRepository brokers;
    private final TriggerTradeRequestRepository requests;
    private final TriggeredTradeSetupRepository setups;
    private final NaturalGasMarketData data;
    private final QuoteCacheService quotes;
    private final TradeExecutionService execution;
    private final StrategySupport support;
    private final NaturalGasDataPolicy dataPolicy;
    private static final StrategyMetadata META=new StrategyMetadata(NaturalGasSession.TEMPLATE,
            "MCX Natural Gas · ATM CE / PE breakout",
            "MCX futures 15-minute EMA20/50 + VWAP; Simulator can opt into a rolling 2h window, broker accounts require the full session. 5-minute breakout/retest. Buys ATM CE/PE. Simulator or Sharekhan only. One entry per broker/day; 25% premium stop, 50% target; required rupee max loss. Entries before 23:00; square-off from 23:20 IST.","AUTO");
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r) {
        if(r.getSymbol()!=null && !r.getSymbol().isBlank() && !"NATURALGAS".equalsIgnoreCase(r.getSymbol().trim())) throw new IllegalArgumentException("This strategy supports MCX NATURALGAS only");
        r.setSymbol("NATURALGAS");r.setIntraday(true);
        if(r.getLots()==null)r.setLots(1);
        if(r.getLots()<1 || r.getLots()>10)throw new IllegalArgumentException("Natural Gas size must be between 1 and 10 lots");
        r.setRiskSettings(org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(r.getRiskSettings(),r.getLots()));
        if(r.getRiskSettings()==null || r.getRiskSettings().getMaxLoss()==null) throw new IllegalArgumentException("Set a positive rupee maximum loss for the Natural Gas strategy run");
    }
    public void validateConfiguration(StrategyApplyRequest r) {
        validate(r);
        var broker=brokers.findById(r.getBrokerCredentialsId()==null?-1:r.getBrokerCredentialsId())
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()))
                .orElseThrow(()->new IllegalArgumentException("Choose an owned broker account"));
        if(!Set.of("SIMULATOR","SHAREKHAN").contains(Objects.toString(broker.getBrokerName(),"").toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("MCX Natural Gas currently supports Simulator and Sharekhan only");
    }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest request){return apply(request,LocalDateTime.now(StrategySupport.MARKET_ZONE));}
    public StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now) {
        validateConfiguration(r);
        if(!NaturalGasSession.entryAllowed(now))return waitFor("Outside MCX entry window 09:00-23:00 IST");
        var unresolvedStatuses=List.of(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
                org.com.sharekhan.enums.TriggeredTradeStatus.ENTRY_SUBMITTING, org.com.sharekhan.enums.TriggeredTradeStatus.TRIGGERED,
                org.com.sharekhan.enums.TriggeredTradeStatus.FAILED);
        boolean unresolvedRequest=requests.findBySourceIgnoreCaseAndStatusIn(NaturalGasSession.SOURCE,unresolvedStatuses).stream()
                .filter(p->Objects.equals(p.getAppUserId(),r.getUserId()) && Objects.equals(p.getBrokerCredentialsId(),r.getBrokerCredentialsId()))
                .anyMatch(p->p.getStatus()!=org.com.sharekhan.enums.TriggeredTradeStatus.FAILED || uncertain(p.getReason()));
        boolean openPosition=setups.findBySource(NaturalGasSession.SOURCE).stream()
                .filter(p->Objects.equals(p.getAppUserId(),r.getUserId()) && Objects.equals(p.getBrokerCredentialsId(),r.getBrokerCredentialsId()))
                .anyMatch(p->p.getStatus()==null || !Set.of(org.com.sharekhan.enums.TriggeredTradeStatus.EXITED_SUCCESS,
                        org.com.sharekhan.enums.TriggeredTradeStatus.COMPLETED, org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED,
                        org.com.sharekhan.enums.TriggeredTradeStatus.CANCELLED, org.com.sharekhan.enums.TriggeredTradeStatus.FAILED).contains(p.getStatus()) || uncertain(p.getReason()));
        if(unresolvedRequest || openPosition) return waitFor("An existing Natural Gas position or uncertain entry must be reconciled before another entry");
        var prior=requests.findNaturalGasDailyRequests(NaturalGasSession.SOURCE,r.getUserId(),r.getBrokerCredentialsId(),now.toLocalDate().atStartOfDay(),now.toLocalDate().plusDays(1).atStartOfDay());
        if(prior.stream().anyMatch(p->!(p.getStatus()==org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED && org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON.equals(p.getReason())))
                || setups.countNaturalGasDailyEntries(NaturalGasSession.SOURCE,r.getUserId(),r.getBrokerCredentialsId(),now.toLocalDate().atStartOfDay(),now.toLocalDate().plusDays(1).atStartOfDay())>0)
            return waitFor("This broker already has a Natural Gas entry or unresolved attempt today");
        var rows=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS");
        var pair=NaturalGasContracts.resolve(rows,now.toLocalDate());var future=pair.future();
        if(future.getTickSize()==null || !Double.isFinite(future.getTickSize()) || future.getTickSize()<=0) return waitFor("Futures tick metadata unavailable");
        data.watch(future.getScripCode());
        execution.warmUpSpotLtp(future,"MCX Natural Gas strategy");
        var brokerName=brokers.findById(r.getBrokerCredentialsId()).orElseThrow().getBrokerName();
        var source=dataPolicy.sourceForBroker(brokerName);
        var candles=source==NaturalGasCandleSource.SHOONYA_TPS?data.shoonyaCandles(future,now):data.candles(future.getScripCode(),now);
        var evaluation=NaturalGasSignals.inspect(candles,now,future.getTickSize(),dataPolicy.forBroker(brokerName),dataPolicy.trendForBroker(brokerName));
        String dataContext=(evaluation.dataContext().isBlank()?"":"; "+evaluation.dataContext())+"; candleSource="+source
                +(source==NaturalGasCandleSource.SHOONYA_TPS?" (prior seed=Sharekhan; today=Shoonya TPSeries)":"");
        log.info("MCX_DATA_EVALUATION | subscription={} broker={} reason={} context={}",r.getSubscriptionId(),brokerName,evaluation.reason(),dataContext);
        if(evaluation.signal().isEmpty()) {
            String reason=evaluation.reason();
            if(reason.startsWith("No MCX candles") || reason.startsWith("No completed MCX") || reason.startsWith("Incomplete MCX") || reason.startsWith("Gap in MCX") || reason.startsWith("Stale MCX"))
                reason += "; "+(source==NaturalGasCandleSource.SHOONYA_TPS?data.shoonyaStatus(future.getScripCode()):data.status(future.getScripCode()));
            return waitFor(reason+dataContext);
        }
        var signal=evaluation.signal().get();
        var futureQuote=quotes.getSnapshot(future.getScripCode()).orElse(null);
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        if (futureQuote==null || futureQuote.getLastTradedPrice()==null || !Double.isFinite(futureQuote.getLastTradedPrice())
                || futureQuote.getLastTradedPrice()<=0 || futureQuote.getLastLtpAt()==null || futureQuote.getLastLtpAt().isAfter(instant)
                || futureQuote.getLastLtpAt().isBefore(instant.minusSeconds(15)) || !"SHAREKHAN_WS".equals(futureQuote.getLtpSource())
                || !("scrip:"+future.getScripCode()).equals(futureQuote.getLtpProviderIdentity()))
            return waitFor("Waiting for fresh exact-contract Sharekhan MCX futures price"+dataContext);
        double price=futureQuote.getLastTradedPrice();
        boolean ce="CE".equals(signal.side());double risk=Math.abs(signal.trigger()-signal.stop());
        if(!(ce?price>=signal.trigger():price<=signal.trigger()) || Math.abs(price-signal.trigger())>.5*risk)return waitFor("Waiting for rejection-candle breakout without chasing more than 0.5R"+dataContext);
        var option=NaturalGasContracts.atm(rows,pair.optionExpiry(),signal.side(),price);
        TriggerRequest t=new TriggerRequest();t.setInstrument("NATURALGAS");t.setExchange("MX");t.setOptionType(signal.side());
        t.setExpiry(option.getExpiry());t.setStrikePrice(option.getStrikePrice());t.setUserId(r.getUserId());t.setBrokerCredentialsId(r.getBrokerCredentialsId());
        t.setSource(NaturalGasSession.SOURCE);t.setAction("BUY");t.setLots(r.getLots());t.setQuantity(r.getLots());t.setIntraday(true);
        execution.warmUpOptionLtp(t,"MCX Natural Gas exact ATM option");
        var q=quotes.getSnapshot(option.getScripCode()).orElse(null);
        if(!usableBook(q,instant,option.getScripCode()))return waitFor("Waiting for fresh coherent ATM option bid/ask (maximum 2% spread)"+dataContext);
        double premium=round(q.getBestAsk(),option.getTickSize(),RoundingMode.CEILING);
        double stop=round(premium*.75,option.getTickSize(),RoundingMode.FLOOR);
        if(stop<=0 || stop>=premium)return waitFor("Option premium too small for a valid tick-aligned stop"+dataContext);
        double estimatedRisk=(premium-stop)*option.getLotSize()*r.getLots();
        var budget=r.getRiskSettings().scale(r.getRiskSettings().getMaxLoss(),r.getLots());
        if(BigDecimal.valueOf(estimatedRisk).compareTo(budget)>0)return waitFor("Selected lots exceed the rupee loss limit at the 25% premium stop; reduce lots or adjust the limit"+dataContext);
        t.setEntryPrice(premium);t.setStopLoss(stop);t.setTarget1(round(premium*1.5,option.getTickSize(),RoundingMode.CEILING));
        t.setUseSpotPrice(false);t.setUseSpotForEntry(false);t.setUseSpotForSl(false);t.setUseSpotForTarget(false);t.setTslEnabled(false);
        var trade=support.executeTriggeredTrade(t);
        return StrategyApplyResponse.builder().templateId(META.id()).symbol("NATURALGAS").direction(signal.side()).status("triggered")
                .message("MCX ATM "+signal.side()+" entry requested; premium SL/target and run risk apply. Simulator assumes full top-of-book quantity; depth is unverified."+dataContext)
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
