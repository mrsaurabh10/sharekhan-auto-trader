package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Exact option price observations using existing read-only providers. No depth or fill guarantee. */
@Service
public class BatmanLtpService {
    private record Key(Integer code,String exchange,String symbol,String side,Double strike,String expiry,Integer lotSize){}
    private record Attempt(Instant attempted,QuoteCacheService.QuoteSnapshot quote){}
    private final QuoteCacheService cache;
    private final ShoonyaQuoteService shoonya;
    private final MStockLtpService mstock;
    private final MStockInstrumentResolver resolver;
    private final Clock clock;
    private final Map<Key,Attempt> attempts=new HashMap<>();
    @Autowired
    public BatmanLtpService(QuoteCacheService cache,ShoonyaQuoteService shoonya,MStockLtpService mstock,MStockInstrumentResolver resolver){
        this(cache,shoonya,mstock,resolver,Clock.systemUTC());
    }
    BatmanLtpService(QuoteCacheService cache,ShoonyaQuoteService shoonya,MStockLtpService mstock,MStockInstrumentResolver resolver,Clock clock){
        this.cache=cache;this.shoonya=shoonya;this.mstock=mstock;this.resolver=resolver;this.clock=clock;
    }
    public synchronized QuoteCacheService.QuoteSnapshot quote(ScriptMasterEntity script){
        if(script==null||script.getScripCode()==null||script.getExchange()==null||!Set.of("NF","BF").contains(script.getExchange())
                ||!Set.of("CE","PE").contains(Objects.toString(script.getOptionType(),""))||script.getStrikePrice()==null||script.getExpiry()==null)return null;
        String exchange=script.getExchange(),providerExchange="NF".equals(exchange)?"NFO":"BFO";
        var now=clock.instant();var q=cache.getSharekhanSnapshot(script.getScripCode()).orElse(null);
        if(q!=null&&Objects.equals(q.getScripCode(),script.getScripCode())&&"SHAREKHAN_WS".equals(q.getLtpSource())
                &&("scrip:"+script.getScripCode()).equals(q.getLtpProviderIdentity())&&(exchange.equals(q.getLtpExchange())||providerExchange.equals(q.getLtpExchange()))
                &&positive(q.getLastTradedPrice())&&recent(q.getLastLtpAt(),now))return book(script,q.getLastTradedPrice(),q.getLastLtpAt(),q.getLtpSource(),q.getLtpProviderIdentity());
        var key=new Key(script.getScripCode(),exchange,script.getTradingSymbol(),script.getOptionType(),script.getStrikePrice(),script.getExpiry(),script.getLotSize());
        attempts.entrySet().removeIf(e->e.getValue().attempted().plusSeconds(60).isBefore(now));
        var old=attempts.get(key);
        if(old!=null&&!old.attempted().isAfter(now)&&old.attempted().plusSeconds(2).isAfter(now))
            return old.quote()!=null&&recent(old.quote().getLastBookAt(),now)?old.quote():null;
        q=fetch(script,providerExchange);attempts.put(key,new Attempt(clock.instant(),q));return q;
    }
    private QuoteCacheService.QuoteSnapshot fetch(ScriptMasterEntity script,String expectedExchange){
        try{
            var response=shoonya.getExactQuote(script);
            if(response.isPresent()){
                var exact=response.get();var q=exact.quote();var now=clock.instant();
                if(q!=null&&q.hasConfirmedIdentity()&&expectedExchange.equals(exact.exchange())&&positive(q.lastPrice())
                        &&recent(exact.receivedAt(),now)&&recent(exact.providerSnapshotAt(),now)
                        &&(exact.providerUpdatedAt()==null||!exact.providerUpdatedAt().isAfter(now)))
                    return book(script,q.lastPrice(),exact.providerSnapshotAt(),"SHOONYA",q.returnedToken()+":"+q.returnedTradingSymbol());
            }
        }catch(RuntimeException unavailable){/* Try the next existing provider. */}
        try{
            var key=resolver.resolveInstrumentKey(script);
            if(key.isEmpty()||!key.get().startsWith(expectedExchange+":"))return null;
            var started=clock.instant();var raw=mstock.fetchLtpForInstrumentReadOnly(key.get());var now=clock.instant();
            if(raw==null||!(raw.get("instrument_token") instanceof Number token)||token.longValue()!=script.getScripCode().longValue()
                    ||token.doubleValue()!=token.longValue()||!(raw.get("last_price") instanceof Number price)||!positive(price.doubleValue())||!recent(started,now))return null;
            // Existing LTP REST has no exchange last-trade timestamp. Record current response time, not a new trade time.
            return book(script,price.doubleValue(),now,"MSTOCK_REST_OBSERVATION",key.get()+":"+token.longValue());
        }catch(RuntimeException unavailable){return null;}
    }
    private static QuoteCacheService.QuoteSnapshot book(ScriptMasterEntity script,double price,Instant at,String provider,String identity){
        // Equal price sides are an explicit Simulator LTP fill assumption; quantities are not invented.
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(script.getScripCode()).bookExchange(script.getExchange()).bookSource(provider+"_SIM_LTP")
                .bookProviderIdentity(identity).bestBid(price).bestAsk(price).bidQuantity(0d).askQuantity(0d).lastBookAt(at).updatedAt(at).coherentBook(true).build();
    }
    private static boolean positive(Double price){return price!=null&&Double.isFinite(price)&&price>0;}
    private static boolean recent(Instant at,Instant now){return at!=null&&!at.isAfter(now)&&!at.isBefore(now.minusSeconds(5));}
}
