package org.com.sharekhan.strategy.adxdmi;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.strategy.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component @RequiredArgsConstructor
public class AdxDmiStrategy implements StrategyEvaluator {
    private final BrokerCredentialsRepository brokers;
    private final ScriptMasterRepository scripts;
    private final StrategySupport support;
    private final AdxDmiMarketData quotes;
    private final TradeExecutionService feeds;
    private final AdxDmiGreeks greeks;
    private final NseMarketCalendar calendar;
    private final AdxDmiContractMetadata contractMetadata;
    @org.springframework.beans.factory.annotation.Value("${app.strategy.adx-dmi.fallback-spot-tick:0.05}")
    private double fallbackSpotTick=0.05;
    private static final StrategyMetadata META=new StrategyMetadata(AdxDmiRules.TEMPLATE,"ADX / DMI Fixed Partials",
            "Symbol input, 5m ADX(14,14)>25; dominant DI>25, opposite DI<20. Next-bar breakout by one underlying tick, three separate one-lot requests at 1R/2R/3R, fixed index SL throughout. Exit remaining lots if index T1 is not observed within 15 minutes of the first fill. Entries before noon IST; square-off 15:20. ATM option executes at its own quoted premium. Skip the first opening candle; selected account executes through the standard order engine.","AUTO");
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r){
        r.setTemplateId(AdxDmiRules.TEMPLATE);r.setSymbol(AdxDmiRules.symbol(r.getSymbol()));r.setIntraday(true);
        if(r.getLots()!=null && r.getLots()!=3)throw new IllegalArgumentException("This strategy requires three equal lots");
        r.setLots(3);r.setSource(AdxDmiRules.PREMIUM_SOURCE);
        var risk=r.getRiskSettings();
        if(risk!=null && (risk.getMaxLoss()!=null || risk.getProfitTarget()!=null || risk.getTrailActivation()!=null || risk.getSlStepProfit()!=null))
            throw new IllegalArgumentException("Frozen comparison uses the original signal stop and no additional run exits");
        r.setRiskSettings(null);
    }
    public void validateConfiguration(StrategyApplyRequest r){
        validate(r);
        var account=brokers.findById(r.getBrokerCredentialsId()==null?-1:r.getBrokerCredentialsId())
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()) && Boolean.TRUE.equals(b.getActive()))
                .orElseThrow(()->new IllegalArgumentException("Choose an active owned broker account"));
        org.com.sharekhan.enums.Broker.fromDisplayName(account.getBrokerName());
    }
    @Override public StrategyApplyResponse apply(StrategyApplyRequest r){return apply(r,LocalDateTime.now(AdxDmiRules.IST));}
    public StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now){
        validateConfiguration(r);String symbol=r.getSymbol();
        if(!calendar.isTradingDay(now.toLocalDate()) || now.toLocalTime().isBefore(LocalTime.of(9,25)) || !now.toLocalTime().isBefore(AdxDmiRules.CUTOFF))return waitFor(symbol,"Waiting for the 09:25–12:00 IST entry window");
        var spot=support.resolveSpotScript(symbol);String cash=Objects.toString(spot.getExchange(),"").trim().toUpperCase(Locale.ROOT);
        if(!Set.of("NC","BC").contains(cash) || spot.getScripCode()==null)throw new IllegalStateException("Exact underlying spot identity unavailable");
        feeds.warmUpSpotLtp(spot,"ADX DMI "+symbol+" spot");
        double spotTick=AdxDmiRules.spotTick(spot.getTickSize(),fallbackSpotTick);
        var signal=AdxDmiRules.latestSignal(candles(spot,now),now,spotTick).orElse(null);
        if(signal==null)return waitFor(symbol,"Waiting for a completed qualifying 5m signal (200+ warmup bars)");
        var q=quotes.spot(spot.getScripCode(),cash);
        if(!quotes.freshSpot(cash,q,java.time.Instant.now(),spot.getScripCode()))return waitFor(symbol,"Waiting for fresh exact spot quote: "+quotes.reason(spot.getScripCode(),cash,false));
        String exchange=AdxDmiRules.optionExchange(cash);
        var contracts=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange,symbol);
        var option=contract(contracts,symbol,exchange,signal.side(),q.getLastTradedPrice(),now.toLocalDate());
        var warm=new TriggerRequest();warm.setInstrument(symbol);warm.setExchange(option.getExchange());warm.setOptionType(signal.side());warm.setExpiry(option.getExpiry());warm.setStrikePrice(option.getStrikePrice());
        feeds.warmUpOptionLtp(warm,"ADX DMI exact ATM option");
        if(!signal.crossed(q.getLastTradedPrice()))return waitFor(symbol,"Waiting for "+signal.side()+" breakout; signal="+signal.key());
        try{
            var reading=greeks.read(spot,option);
            var actualNow=LocalDateTime.now(AdxDmiRules.IST);
            if(!signal.inWindow(actualNow))return waitFor(symbol,"Signal expired while preparing the option order");
            q=quotes.spot(spot.getScripCode(),cash);
            if(!quotes.freshSpot(cash,q,Instant.now(),spot.getScripCode()) || !signal.crossed(q.getLastTradedPrice()))
                return waitFor(symbol,"Waiting for a fresh confirmed breakout");
            if(!Objects.equals(option.getScripCode(),contract(contracts,symbol,exchange,signal.side(),q.getLastTradedPrice(),actualNow.toLocalDate()).getScripCode()))
                return waitFor(symbol,"ATM changed during preparation; re-evaluate the selected contract");
            var book=quotes.book(option.getScripCode(),exchange);
            if(!quotes.freshBook(exchange,book,Instant.now(),option.getScripCode()))return waitFor(symbol,"Waiting for a fresh exact option book");
            double optionTick=contractMetadata.optionTick(option,actualNow);
            var plan=AdxDmiIndexPlan.create(book.getBestAsk(),q.getLastTradedPrice(),signal,optionTick);
            var orders=orders(r,signal,option,spot.getScripCode(),plan,reading);
            var requests=feeds.executeAdxDmiRequests(orders);
            if(requests.size()!=3)return waitFor(symbol,"This signal was already processed; waiting for the next qualifying candle");
            var req=requests.getFirst();
            return StrategyApplyResponse.builder().templateId(META.id()).symbol(symbol).direction(signal.side()).status("triggered")
                    .message("Three separate one-lot option BUY requests with the same fixed index SL and index targets 1R/2R/3R; no-T1 timeout 15 minutes. Delta audit: "+reading.source()).tradeRequest(req).breakoutClose(q.getLastTradedPrice()).build();
        }catch(IllegalStateException waiting){return waitFor(symbol,waiting.getMessage());}
    }
    static List<TriggerRequest> orders(StrategyApplyRequest r,AdxDmiRules.Signal signal,ScriptMasterEntity option,int spotCode,
            AdxDmiIndexPlan plan,AdxDmiGreeks.Reading reading){
        var orders=new ArrayList<TriggerRequest>();
        String timeGroup=java.util.UUID.randomUUID().toString();
        for(int leg=1;leg<=3;leg++){
            var order=order(r,signal,option,spotCode,plan,reading);
            var settings=new org.com.sharekhan.execution.TradeExecutionSettings();
            settings.setMarketableEntry(true);settings.setRequireDisplayedDepth(false);
            settings.setTimeExitMinutes(15);settings.setTimeExitUseSpot(true);settings.setTimeExitProgressPrice(plan.t1());
            settings.setTimeExitProgressDirection("PE".equalsIgnoreCase(signal.side())
                    ? org.com.sharekhan.execution.TradeExecutionSettings.Direction.BELOW : org.com.sharekhan.execution.TradeExecutionSettings.Direction.ABOVE);
            settings.setTimeExitGroupId(timeGroup);settings.setSpotStopOnTouch(true);settings.setRequireProfitableSpotTarget(false);
            order.setExecutionSettings(settings);
            order.setTarget1(leg==1?plan.t1():leg==2?plan.t2():plan.t3());
            order.setStrategyDecision(order.getStrategyDecision()+"ADX_LEG="+leg+";");orders.add(order);
        }
        return List.copyOf(orders);
    }
    private static TriggerRequest order(StrategyApplyRequest r,AdxDmiRules.Signal signal,ScriptMasterEntity option,int spotCode,
            AdxDmiIndexPlan plan,AdxDmiGreeks.Reading reading){
        var order=new TriggerRequest();order.setInstrument(r.getSymbol());order.setExchange(option.getExchange());
        order.setOptionType(signal.side());order.setExpiry(option.getExpiry());order.setStrikePrice(option.getStrikePrice());
        order.setEntryPrice(plan.entry());order.setStopLoss(plan.stop());order.setTarget1(plan.t1());
        order.setQuantity(1);order.setLots(1);order.setUserId(r.getUserId());order.setBrokerCredentialsId(r.getBrokerCredentialsId());
        order.setIntraday(true);order.setAction("BUY");order.setTslEnabled(false);
        order.setUseSpotPrice(false);order.setUseSpotForEntry(false);order.setUseSpotForSl(true);order.setUseSpotForTarget(true);order.setSpotScripCode(spotCode);
        order.setSource(AdxDmiRules.PREMIUM_SOURCE);
        order.setStrategyDecision("ADX_SIGNAL="+signal.key()+";ADX_DEADLINE="+signal.end().plusMinutes(5)+";ADX_EXIT="+"INDEX_REQUEST_V2"+";ADX_ENTRY=MARKETABLE_LIMIT_V1;ADX_INDEX_ENTRY="+plan.spotEntry()+";ADX_INDEX_T1="+plan.t1()+";delta="+reading.delta()+";source="+reading.source()
                +";gamma="+reading.gamma()+";vega="+reading.vega()+";theta="+reading.theta()+";estimatedPremiumR="+(signal.risk(plan.spotEntry())*reading.delta())+";");
        return order;
    }
    static ScriptMasterEntity contract(List<ScriptMasterEntity> rows,String symbol,String exchange,String side,double spot,LocalDate day){
        AdxDmiRules.symbol(symbol);AdxDmiRules.cashExchange(exchange);
        var available=rows.stream().filter(s->symbol.equalsIgnoreCase(s.getTradingSymbol()) && side.equalsIgnoreCase(s.getOptionType())
                && exchange.equalsIgnoreCase(s.getExchange()) && s.getScripCode()!=null && s.getStrikePrice()!=null && Double.isFinite(s.getStrikePrice()) && s.getStrikePrice()>0
                && expiry(s.getExpiry())!=null && !expiry(s.getExpiry()).isBefore(day)).toList();
        var expiry=available.stream().map(s->expiry(s.getExpiry())).min(Comparator.naturalOrder()).orElseThrow(()->new IllegalStateException("No listed "+symbol+" "+side+" expiry available"));
        return available.stream().filter(s->expiry.equals(expiry(s.getExpiry())))
                .min(Comparator.comparingDouble((ScriptMasterEntity s)->Math.abs(s.getStrikePrice()-spot)).thenComparingDouble(ScriptMasterEntity::getStrikePrice).thenComparingInt(ScriptMasterEntity::getScripCode)).orElseThrow();
    }
    private static LocalDate expiry(String text){if(text==null)return null;for(var f:List.of(DateTimeFormatter.ofPattern("dd/MM/uuuu"),DateTimeFormatter.ISO_LOCAL_DATE))try{return LocalDate.parse(text.trim(),f);}catch(java.time.format.DateTimeParseException ignored){ }return null;}
    private final Map<Integer,CachedBars> history=new HashMap<>();
    private record CachedBars(LocalDateTime refreshed,List<StrategyCandle> bars) { }
    private synchronized List<StrategyCandle> candles(ScriptMasterEntity spot,LocalDateTime now){
        var cached=history.get(spot.getScripCode());
        var bucket=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
        boolean missing=cached==null || cached.bars().isEmpty() || AdxDmiRules.start(cached.bars().getLast()).plusMinutes(5).isBefore(bucket);
        if(cached==null || cached.refreshed().isAfter(now) || missing && !cached.refreshed().plusSeconds(5).isAfter(now)){
            cached=new CachedBars(now,List.copyOf(support.loadCompletedIndicatorCandles(spot,200,now).candles()));history.put(spot.getScripCode(),cached);
        }
        return cached.bars();
    }
    private StrategyApplyResponse waitFor(String symbol,String message){return support.waiting(META,symbol,message);}
}
