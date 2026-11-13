package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class NiftyThreeCandleStrategy implements StrategyEvaluator {
    private final BrokerCredentialsRepository brokers;
    private final ScriptMasterRepository scripts;
    private final StrategySupport support;
    private final NiftyThreeCandleMarketData data;
    private final QuoteCacheService quotes;
    private final TradeExecutionService feeds;
    private final NiftyThreeCandlePaperBroker paper;
    private final NseMarketCalendar calendar;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private SpotQuoteService spotQuotes;
    private QuoteCacheService.QuoteSnapshot spotQuote(int code) {
        return spotQuotes==null?quotes.getSnapshot(code).orElse(null):spotQuotes.spot(code,"NC");
    }
    private boolean freshSpot(QuoteCacheService.QuoteSnapshot quote,Instant at,int code) {
        return spotQuotes==null?NiftyThreeCandleRules.freshSpot(quote,at,code):spotQuotes.fresh(quote,at,code,"NC");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NiftyThreeCandleStrategy(BrokerCredentialsRepository brokers, ScriptMasterRepository scripts,
            StrategySupport support, NiftyThreeCandleMarketData data, QuoteCacheService quotes,
            TradeExecutionService feeds, NiftyThreeCandlePaperBroker paper, NseMarketCalendar calendar) {
        this(brokers,scripts,support,data,quotes,feeds,paper,calendar,Clock.system(NiftyThreeCandleRules.IST));
    }
    NiftyThreeCandleStrategy(BrokerCredentialsRepository brokers, ScriptMasterRepository scripts,
            StrategySupport support, NiftyThreeCandleMarketData data, QuoteCacheService quotes,
            TradeExecutionService feeds, NiftyThreeCandlePaperBroker paper, NseMarketCalendar calendar, Clock clock) {
        this.brokers=brokers;this.scripts=scripts;this.support=support;this.data=data;this.quotes=quotes;
        this.feeds=feeds;this.paper=paper;this.calendar=calendar;this.clock=clock;
    }
    private static final StrategyMetadata META=new StrategyMetadata(NiftyThreeCandleRules.TEMPLATE,"Nifty Three Candles — Simulator",
            "Nifty 5-minute three green/rising EMA20 → ATM CE, or three red/falling EMA20 → ATM PE. Fourth-candle breakout, C3 spot stop, 1R target. One lot, one shared position, ₹5,000 combined daily paper loss control; exits at 15:30. Paper ask/bid fills require displayed depth. PE is a mirrored trial, not backtested here.","AUTO");
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r){
        r.setTemplateId(NiftyThreeCandleRules.TEMPLATE);
        if(r.getSymbol()!=null && !r.getSymbol().isBlank() && !Set.of("NIFTY","NIFTY50").contains(r.getSymbol().trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("This trial supports NIFTY only");
        r.setSymbol("NIFTY");r.setIntraday(true);
        if(r.getLots()!=null && r.getLots()!=1)throw new IllegalArgumentException("Nifty three-candle paper trial uses one lot");
        r.setLots(1);
        var risk=StrategyRiskSettings.validate(r.getRiskSettings(),1);
        if(risk!=null && (risk.getMaxLoss()!=null && risk.getMaxLoss().compareTo(BigDecimal.valueOf(5000))!=0
                || risk.getProfitTarget()!=null || risk.getTrailActivation()!=null || risk.getSlStepProfit()!=null))
            throw new IllegalArgumentException("This frozen trial uses a ₹5,000 total daily loss control and no run profit/trailing rules");
        risk=new StrategyRiskSettings();risk.setAmountMode(StrategyRiskSettings.AmountMode.TOTAL);risk.setMaxLoss(new BigDecimal("5000.00"));
        r.setRiskSettings(risk);r.setSource(NiftyThreeCandleRules.SOURCE);
    }
    public void validateConfiguration(StrategyApplyRequest r){
        validate(r);
        brokers.findById(r.getBrokerCredentialsId()==null?-1:r.getBrokerCredentialsId())
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()) && Boolean.TRUE.equals(b.getActive())
                        && "SIMULATOR".equalsIgnoreCase(b.getBrokerName()))
                .orElseThrow(()->new IllegalArgumentException("Choose an active owned Simulator account"));
    }
    @Override public StrategyApplyResponse apply(StrategyApplyRequest r){return apply(r,LocalDateTime.now(clock));}
    public StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now){
        validateConfiguration(r);
        if(!calendar.isTradingDay(now.toLocalDate()) || now.toLocalTime().isBefore(LocalTime.of(9,30)) || !now.toLocalTime().isBefore(LocalTime.of(15,30)))
            return waitFor("Waiting for the regular Nifty session");
        var spot=support.resolveSpotScript("NIFTY");
        if(!"NC".equalsIgnoreCase(spot.getExchange()) || spot.getScripCode()==null)throw new IllegalStateException("NSE Nifty spot identity is unavailable");
        var bars=data.candles(spot,now);
        var check=NiftyThreeCandleRules.check(bars,now);
        var signal=check.signal();
        if(signal==null)return waitFor(check.reason());
        var q=spotQuote(spot.getScripCode());
        // Read the clock after the snapshot: ticks received while candles load are not future data.
        var observedAt=clock.instant();
        if(!signal.inWindow(LocalDateTime.ofInstant(observedAt,NiftyThreeCandleRules.IST)))
            return waitFor("The fourth-candle entry window has expired; signal="+signal.key());
        if(!freshSpot(q,observedAt,spot.getScripCode()))return waitFor("Waiting for verified Nifty spot quote"+(spotQuotes==null?"":": "+spotQuotes.reason(spot.getScripCode(),"NC")));
        var contracts=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("NF","NIFTY");
        var option=contract(contracts,signal.side(),q.getLastTradedPrice(),now.toLocalDate());
        var warm=new TriggerRequest();warm.setInstrument("NIFTY");warm.setExchange(option.getExchange());warm.setOptionType(signal.side());warm.setExpiry(option.getExpiry());warm.setStrikePrice(option.getStrikePrice());
        feeds.warmUpOptionLtp(warm,"Nifty three-candle exact ATM option");
        if(!signal.crossed(q.getLastTradedPrice()))return waitFor("Waiting for "+signal.side()+" breakout during the fourth candle; signal="+signal.key());
        try {
            var req=paper.open(r,signal,option,spot.getScripCode(),contracts);
            return StrategyApplyResponse.builder().templateId(META.id()).symbol("NIFTY").direction(signal.side()).status("triggered")
                    .message("Paper "+signal.side()+" opened at option ask; fixed C3 spot stop and 1R target; ₹5,000 combined daily control. Charges use the app's estimate.")
                    .tradeRequest(req).breakoutClose(q.getLastTradedPrice()).build();
        } catch(IllegalStateException waiting){return waitFor(waiting.getMessage());}
    }
    static ScriptMasterEntity contract(List<ScriptMasterEntity> rows,String side,double spot,LocalDate day){
        var available=rows.stream().filter(s->"NIFTY".equalsIgnoreCase(s.getTradingSymbol()) && side.equalsIgnoreCase(s.getOptionType())
                && Set.of("NF","NFO").contains(Objects.toString(s.getExchange(),"")) && s.getScripCode()!=null
                && s.getStrikePrice()!=null && Double.isFinite(s.getStrikePrice()) && s.getStrikePrice()>0
                && expiry(s.getExpiry())!=null && !expiry(s.getExpiry()).isBefore(day)).toList();
        var expiry=available.stream().map(s->expiry(s.getExpiry())).min(Comparator.naturalOrder())
                .orElseThrow(()->new IllegalStateException("No listed Nifty "+side+" expiry available"));
        return available.stream().filter(s->expiry.equals(expiry(s.getExpiry())))
                .min(Comparator.comparingDouble((ScriptMasterEntity s)->Math.abs(s.getStrikePrice()-spot)).thenComparingDouble(ScriptMasterEntity::getStrikePrice)
                        .thenComparingInt(ScriptMasterEntity::getScripCode)).orElseThrow();
    }
    private static LocalDate expiry(String s){
        if(s==null)return null;
        for(var f:List.of(DateTimeFormatter.ofPattern("dd/MM/uuuu"),DateTimeFormatter.ISO_LOCAL_DATE))try{return LocalDate.parse(s.trim(),f);}catch(java.time.format.DateTimeParseException ignored){ }
        return null;
    }
    private StrategyApplyResponse waitFor(String message){return support.waiting(META,"NIFTY",message);}
}
