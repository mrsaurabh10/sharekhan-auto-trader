package org.com.sharekhan.service;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared spot observation, independent of the account used to execute an order. */
@Service
public class SpotQuoteService {
    private record Key(int code,String exchange) { }
    private record Attempt(Instant at,Instant providerAt,QuoteCacheService.QuoteSnapshot quote,String reason) { }
    private final QuoteCacheService cache;
    private final ScriptMasterRepository scripts;
    private final ShoonyaQuoteService shoonya;
    private final MStockLtpService mstock;
    private final MStockInstrumentResolver resolver;
    private final WebSocketSubscriptionService websocket;
    private final Clock clock;
    private final Map<Key,Attempt> attempts=new ConcurrentHashMap<>();
    @Autowired
    public SpotQuoteService(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya,
            MStockLtpService mstock,MStockInstrumentResolver resolver,WebSocketSubscriptionService websocket) {
        this(cache,scripts,shoonya,mstock,resolver,websocket,Clock.systemUTC());
    }
    SpotQuoteService(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya,
            MStockLtpService mstock,MStockInstrumentResolver resolver,WebSocketSubscriptionService websocket,Clock clock) {
        this.cache=cache;this.scripts=scripts;this.shoonya=shoonya;this.mstock=mstock;this.resolver=resolver;this.websocket=websocket;this.clock=clock;
    }
    public QuoteCacheService.QuoteSnapshot spot(int code,String exchange) {
        var script=scripts.findByScripCode(code);
        if(!spotScript(script,exchange))return null;
        String feed=exchange+code;
        var active=websocket.getActiveScripKeys();
        if(active==null||!active.contains(feed))websocket.subscribeToScripLtp(feed);
        var q=cache.getSharekhanSnapshot(code).orElse(null);
        if(websocketQuote(q,clock.instant(),code,exchange))return q;
        var key=new Key(code,exchange);
        attempts.entrySet().removeIf(e->e.getValue().at().plusSeconds(60).isBefore(clock.instant()));
        if(attempts.size()>=2048&&!attempts.containsKey(key))return null;
        var attempt=attempts.compute(key,(k,old)->old!=null&&!old.at().isAfter(clock.instant())&&old.at().plusSeconds(2).isAfter(clock.instant())?old:fetch(script,k));
        // A websocket frame may arrive while a fallback is being fetched.
        q=cache.getSharekhanSnapshot(code).orElse(null);
        if(websocketQuote(q,clock.instant(),code,exchange))return q;
        return issued(key,attempt.quote(),clock.instant())?attempt.quote():null;
    }
    /** Nonblocking observation for protective exits and diagnostics; never invokes a provider. */
    public QuoteCacheService.QuoteSnapshot cached(int code,String exchange) {
        var q=cache.getSharekhanSnapshot(code).orElse(null);var now=clock.instant();
        if(websocketQuote(q,now,code,exchange))return q;
        var key=new Key(code,exchange);var attempt=attempts.get(key);
        return attempt!=null&&issued(key,attempt.quote(),now)?attempt.quote():null;
    }
    public boolean fresh(QuoteCacheService.QuoteSnapshot q,Instant at,int code,String exchange) {
        return websocketQuote(q,at,code,exchange)||issued(new Key(code,exchange),q,at);
    }
    private boolean issued(Key key,QuoteCacheService.QuoteSnapshot q,Instant now) {
        var attempt=attempts.get(key);
        return q!=null&&attempt!=null&&attempt.quote()==q&&recent(attempt.providerAt(),now)
                &&recent(q.getLastLtpAt(),now)&&Objects.equals(q.getScripCode(),key.code())&&key.exchange().equals(q.getLtpExchange());
    }
    private static boolean recent(Instant at,Instant now){return at!=null&&!at.isAfter(now)&&!at.isBefore(now.minusSeconds(5));}
    private static boolean usable(Double price){return price!=null&&Double.isFinite(price)&&price>0;}
    private static boolean websocketQuote(QuoteCacheService.QuoteSnapshot q,Instant at,int code,String exchange) {
        String providerExchange="NC".equals(exchange)?"NSE":"BC".equals(exchange)?"BSE":"";
        return !providerExchange.isEmpty()&&q!=null&&Objects.equals(q.getScripCode(),code)&&usable(q.getLastTradedPrice())
                &&"SHAREKHAN_WS".equals(q.getLtpSource())&&(exchange.equals(q.getLtpExchange())||providerExchange.equals(q.getLtpExchange()))
                &&("scrip:"+code).equals(q.getLtpProviderIdentity())&&recent(q.getLastLtpAt(),at);
    }
    private static boolean spotScript(ScriptMasterEntity script,String exchange) {
        return script!=null&&Set.of("NC","BC").contains(exchange)&&exchange.equals(script.getExchange())
                &&(script.getOptionType()==null||script.getOptionType().isBlank())&&script.getTradingSymbol()!=null;
    }
    private Attempt fetch(ScriptMasterEntity script,Key key) {
        String failure="SHOONYA_UNAVAILABLE";
        try {
            var response=shoonya.getExactQuote(script);
            if(response.isPresent()) {
                var exact=response.get();var q=exact.quote();var at=clock.instant();
                String expected="NC".equals(key.exchange())?"NSE":"BSE";
                if(q!=null&&q.hasConfirmedIdentity()&&expected.equals(exact.exchange())&&usable(q.lastPrice())
                        &&recent(exact.receivedAt(),at)&&recent(exact.providerSnapshotAt(),at)
                        &&(exact.providerUpdatedAt()==null||!exact.providerUpdatedAt().isAfter(at)))
                    return accepted(key,"SHOONYA",q.returnedToken()+":"+q.returnedTradingSymbol(),q.lastPrice(),exact.receivedAt(),exact.providerSnapshotAt());
                failure="SHOONYA_IDENTITY_PRICE_OR_TIMESTAMP_REJECTED";
            }
        } catch(RuntimeException unavailable){failure="SHOONYA_UNAVAILABLE";}
        try {
            var resolved=resolver.resolveInstrumentKey(script);
            if(resolved.isEmpty())return rejected(failure+"; MSTOCK_MAPPING_UNAVAILABLE");
            String instrumentKey=resolved.get();String expected="NC".equals(key.exchange())?"NSE":"BSE";
            var expectedToken=resolver.expectedSpotToken(script,instrumentKey);
            if(!instrumentKey.startsWith(expected+":")||expectedToken.isEmpty())
                return rejected(failure+"; MSTOCK_MAPPING_UNAVAILABLE");
            var started=clock.instant();var raw=mstock.fetchLtpForInstrumentReadOnly(instrumentKey);var at=clock.instant();
            if(raw==null||!(raw.get("instrument_token") instanceof Number token)||token.longValue()!=expectedToken.get()
                    ||token.doubleValue()!=token.longValue()||!(raw.get("last_price") instanceof Number price)||!usable(price.doubleValue())||!recent(started,at))
                return rejected(failure+"; MSTOCK_IDENTITY_PRICE_OR_LATENCY_REJECTED");
            // LTP REST supplies no last-change timestamp: this is a verified current response observation.
            return accepted(key,"MSTOCK",instrumentKey+":"+expectedToken.get(),price.doubleValue(),at,at);
        } catch(RuntimeException unavailable){return rejected(failure+"; MSTOCK_UNAVAILABLE");}
    }
    private Attempt accepted(Key key,String source,String identity,double price,Instant received,Instant providerAt) {
        var quote=QuoteCacheService.QuoteSnapshot.builder().scripCode(key.code()).ltpExchange(key.exchange()).ltpSource(source)
                .ltpProviderIdentity(identity).lastTradedPrice(price).lastLtpAt(received).updatedAt(received).build();
        return new Attempt(clock.instant(),providerAt,quote,source+"_VERIFIED");
    }
    private Attempt rejected(String reason){return new Attempt(clock.instant(),null,null,reason);}
    /** Cache inspection only: never subscribes, fetches, or evaluates orders. */
    public Map<String,Object> diagnostics(int code,String exchange) {
        var out=new LinkedHashMap<String,Object>();var now=clock.instant();var q=cache.getSharekhanSnapshot(code).orElse(null);var attempt=attempts.get(new Key(code,exchange));
        out.put("scripCode",code);out.put("exchange",exchange);out.put("checkedAt",now);out.put("sharekhan",q);
        out.put("websocketSubscription",websocket.ltpFeedStatus(exchange+code));
        out.put("sharekhanAccepted",websocketQuote(q,now,code,exchange));out.put("latestSharedCache",cache.getSnapshot(code).orElse(null));
        out.put("fallback",attempt==null?null:attempt.quote());out.put("fallbackProviderAt",attempt==null?null:attempt.providerAt());
        out.put("fallbackAccepted",attempt!=null&&issued(new Key(code,exchange),attempt.quote(),now));
        out.put("reason",attempt==null?"NO_FALLBACK_ATTEMPT":attempt.reason());return out;
    }
    public String reason(int code,String exchange) {
        var key=new Key(code,exchange);var attempt=attempts.get(key);
        if(attempt==null)return "SPOT_QUOTE_UNAVAILABLE";
        return attempt.quote()!=null&&!issued(key,attempt.quote(),clock.instant())?"SPOT_QUOTE_EXPIRED":attempt.reason();
    }
}
