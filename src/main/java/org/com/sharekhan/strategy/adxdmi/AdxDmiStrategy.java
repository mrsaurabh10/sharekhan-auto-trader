package org.com.sharekhan.strategy.adxdmi;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.cache.QuoteCacheService;
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
    private final QuoteCacheService quotes;
    private final TradeExecutionService feeds;
    private final AdxDmiPaperBroker paper;
    private final NseMarketCalendar calendar;
    private static final StrategyMetadata META=new StrategyMetadata(AdxDmiRules.TEMPLATE,"ADX / DMI Fixed Partials — Simulator",
            "NIFTY or SENSEX, 5m ADX(14,14)>25; dominant DI>25, opposite DI<20. Next-bar breakout, 3 lots at 1R/2R/3R, original spot SL throughout. Entries before noon IST; square-off 15:20. Option ask/bid paper fills; 0.5 delta estimates only.","AUTO");
    @Override public StrategyMetadata metadata(){return META;}
    public static void validate(StrategyApplyRequest r){
        r.setTemplateId(AdxDmiRules.TEMPLATE);r.setSymbol(AdxDmiRules.symbol(r.getSymbol()));r.setIntraday(true);
        if(r.getLots()!=null && r.getLots()!=3)throw new IllegalArgumentException("This strategy requires three equal lots");
        r.setLots(3);r.setSource(AdxDmiRules.SOURCE);
        var risk=r.getRiskSettings();
        if(risk!=null && (risk.getMaxLoss()!=null || risk.getProfitTarget()!=null || risk.getTrailActivation()!=null || risk.getSlStepProfit()!=null))
            throw new IllegalArgumentException("Frozen comparison uses the original signal stop and no additional run exits");
        r.setRiskSettings(null);
    }
    public void validateConfiguration(StrategyApplyRequest r){
        validate(r);
        brokers.findById(r.getBrokerCredentialsId()==null?-1:r.getBrokerCredentialsId())
                .filter(b->r.getUserId()!=null && Objects.equals(b.getAppUserId(),r.getUserId()) && Boolean.TRUE.equals(b.getActive()) && "SIMULATOR".equalsIgnoreCase(b.getBrokerName()))
                .orElseThrow(()->new IllegalArgumentException("Choose an active owned Simulator account"));
    }
    @Override public StrategyApplyResponse apply(StrategyApplyRequest r){return apply(r,LocalDateTime.now(AdxDmiRules.IST));}
    public StrategyApplyResponse apply(StrategyApplyRequest r,LocalDateTime now){
        validateConfiguration(r);String symbol=r.getSymbol();
        if(!calendar.isTradingDay(now.toLocalDate()) || now.toLocalTime().isBefore(LocalTime.of(9,20)) || !now.toLocalTime().isBefore(AdxDmiRules.CUTOFF))return waitFor(symbol,"Waiting for the 09:20–12:00 IST entry window");
        var spot=support.resolveSpotScript(symbol);String cash="NIFTY".equals(symbol)?"NC":"BC";
        if(!cash.equalsIgnoreCase(spot.getExchange()) || spot.getScripCode()==null)throw new IllegalStateException("Exact index spot identity unavailable");
        feeds.warmUpSpotLtp(spot,"ADX DMI "+symbol+" spot");
        var signal=AdxDmiRules.latestSignal(candles(spot,now),now).orElse(null);
        if(signal==null)return waitFor(symbol,"Waiting for a completed qualifying 5m signal (200+ warmup bars)");
        var q=quotes.getSnapshot(spot.getScripCode()).orElse(null);
        if(!AdxDmiRules.freshSpot(symbol,q,now.atZone(AdxDmiRules.IST).toInstant(),spot.getScripCode()))return waitFor(symbol,"Waiting for fresh exact spot quote");
        String exchange="NIFTY".equals(symbol)?"NF":"BF";
        var contracts=scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange,symbol);
        var option=contract(contracts,symbol,signal.side(),q.getLastTradedPrice(),now.toLocalDate());
        var warm=new TriggerRequest();warm.setInstrument(symbol);warm.setExchange(option.getExchange());warm.setOptionType(signal.side());warm.setExpiry(option.getExpiry());warm.setStrikePrice(option.getStrikePrice());
        feeds.warmUpOptionLtp(warm,"ADX DMI exact ATM option");
        if(!signal.crossed(q.getLastTradedPrice()))return waitFor(symbol,"Waiting for "+signal.side()+" breakout; signal="+signal.key());
        try{
            var req=paper.open(r,signal,option,spot.getScripCode(),contracts);
            return StrategyApplyResponse.builder().templateId(META.id()).symbol(symbol).direction(signal.side()).status("triggered")
                    .message("Three paper option lots opened at ask. Fixed original spot stop; one lot each at 1R/2R/3R.").tradeRequest(req).breakoutClose(q.getLastTradedPrice()).build();
        }catch(IllegalStateException waiting){return waitFor(symbol,waiting.getMessage());}
    }
    static ScriptMasterEntity contract(List<ScriptMasterEntity> rows,String symbol,String side,double spot,LocalDate day){
        String exchange="NIFTY".equals(AdxDmiRules.symbol(symbol))?"NF":"BF";
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
