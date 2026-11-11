package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ShoonyaQuoteService;
import org.com.sharekhan.service.TradeAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Strategy-local verified REST fallback. Never relabels another provider's shared cache entry. */
@Service
public class AdxDmiMarketData {
    private final QuoteCacheService cache;
    private final ScriptMasterRepository scripts;
    private final ShoonyaQuoteService shoonya;
    private final TradeAuditService audit;
    private final Clock clock;
    private record Key(int code, String exchange, boolean book) { }
    private record Attempt(Instant at, QuoteCacheService.QuoteSnapshot quote, Instant providerAt, String reason) { }
    private record Notice(Instant at, String reason) { }
    private final Map<Key,Attempt> attempts=new ConcurrentHashMap<>();
    private final Map<Key,Notice> notices=new ConcurrentHashMap<>();

    @Autowired
    public AdxDmiMarketData(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya,TradeAuditService audit) {
        this(cache,scripts,shoonya,audit,Clock.system(AdxDmiRules.IST));
    }
    AdxDmiMarketData(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya,TradeAuditService audit,Clock clock) {
        this.cache=cache;this.scripts=scripts;this.shoonya=shoonya;this.audit=audit;this.clock=clock;
    }
    public QuoteCacheService.QuoteSnapshot spot(int code,String exchange){return read(new Key(code,exchange,false));}
    public QuoteCacheService.QuoteSnapshot book(int code,String exchange){return read(new Key(code,exchange,true));}
    public boolean freshSpot(String exchange,QuoteCacheService.QuoteSnapshot q,Instant at,int code){
        return AdxDmiRules.freshSpot(exchange,q,at,code) || issued(new Key(code,exchange,false),q,at);
    }
    public boolean freshBook(String exchange,QuoteCacheService.QuoteSnapshot q,Instant at,int code){
        return AdxDmiRules.freshBook(exchange,q,at,code) || issued(new Key(code,exchange,true),q,at);
    }
    private boolean issued(Key key,QuoteCacheService.QuoteSnapshot q,Instant at){
        var issued=attempts.get(key);
        // Reference equality ties the immutable snapshot to a response validated by this service.
        return issued!=null && q!=null && issued.quote()==q && recent(issued.providerAt(),at)
                && recent(key.book()?q.getLastBookAt():q.getLastLtpAt(),at);
    }
    private boolean recent(Instant time,Instant now){return time!=null && !time.isAfter(now) && !time.isBefore(now.minusSeconds(5));}
    private QuoteCacheService.QuoteSnapshot read(Key key){
        var shared=cache.getSnapshot(key.code()).orElse(null);var now=clock.instant();
        if(key.book()?AdxDmiRules.freshBook(key.exchange(),shared,now,key.code()):AdxDmiRules.freshSpot(key.exchange(),shared,now,key.code()))return shared;
        var attempt=attempts.compute(key,(k,old)->{
            if(old!=null && !old.at().isAfter(clock.instant()) && old.at().plusSeconds(2).isAfter(clock.instant()))return old;
            return fetch(k);
        });
        boolean accepted=issued(key,attempt.quote(),clock.instant());
        report(key,accepted?"SHOONYA_VERIFIED":rejection(key,attempt),shared,attempt);
        return accepted?attempt.quote():null;
    }
    private Attempt fetch(Key key){
        var start=clock.instant();
        var script=scripts.findByScripCode(key.code());
        if(script==null || !key.exchange().equals(script.getExchange()))return new Attempt(start,null,null,"SCRIPT_IDENTITY_UNAVAILABLE");
        var response=shoonya.getExactQuote(script);
        if(response.isEmpty())return new Attempt(clock.instant(),null,null,"EXACT_API_QUOTE_UNAVAILABLE");
        var exact=response.get();var q=exact.quote();var at=clock.instant();
        String expected=switch(key.exchange()){case "NC"->"NSE";case "BC"->"BSE";case "NF"->"NFO";case "BF"->"BFO";default->"";};
        if(q==null || !q.hasConfirmedIdentity() || expected.isEmpty() || !expected.equals(exact.exchange()))
            return new Attempt(at,null,exact.providerUpdatedAt(),"API_IDENTITY_MISMATCH");
        if(!recent(exact.receivedAt(),at) || !recent(exact.providerUpdatedAt(),at))
            return new Attempt(at,null,exact.providerUpdatedAt(),"API_QUOTE_STALE_OR_TIME_MISSING");
        boolean book=q.bestBid()!=null && q.bestAsk()!=null && Double.isFinite(q.bestBid()) && Double.isFinite(q.bestAsk())
                && q.bestBid()>0 && q.bestAsk()>0 && q.bestBid()<=q.bestAsk();
        boolean ltp=q.lastPrice()!=null && Double.isFinite(q.lastPrice()) && q.lastPrice()>0;
        if(key.book()?!book:!ltp)return new Attempt(at,null,exact.providerUpdatedAt(),key.book()?"API_BOOK_UNAVAILABLE":"API_LTP_UNAVAILABLE");
        String identity=q.returnedToken()+":"+q.returnedTradingSymbol();
        var snapshot=QuoteCacheService.QuoteSnapshot.builder().scripCode(key.code())
                .ltpSource("SHOONYA").ltpExchange(key.exchange()).ltpProviderIdentity(identity)
                .lastTradedPrice(q.lastPrice()).lastLtpAt(ltp?exact.receivedAt():null)
                .bookSource("SHOONYA").bookExchange(key.exchange()).bookProviderIdentity(identity)
                .bestBid(q.bestBid()).bestAsk(q.bestAsk()).bidQuantity(q.bidQuantity()).askQuantity(q.askQuantity())
                .coherentBook(book).lastBookAt(book?exact.receivedAt():null).updatedAt(exact.receivedAt()).build();
        return new Attempt(at,snapshot,exact.providerUpdatedAt(),"SHOONYA_VERIFIED");
    }
    public String reason(int code,String exchange,boolean book){
        var a=attempts.get(new Key(code,exchange,book));
        return a==null?"NO_QUOTE":rejection(new Key(code,exchange,book),a);
    }
    private String rejection(Key key,Attempt attempt){
        return attempt.quote()!=null && !issued(key,attempt.quote(),clock.instant())?"API_QUOTE_STALE_OR_TIME_MISSING":attempt.reason();
    }
    private void report(Key key,String reason,QuoteCacheService.QuoteSnapshot shared,Attempt attempt){
        notices.compute(key,(k,previous)->{
            var now=clock.instant();
            if(previous!=null && previous.reason().equals(reason) && previous.at().plusSeconds(60).isAfter(now))return previous;
            var script=scripts.findByScripCode(key.code());
            String detail="scrip="+key.code()+", exchange="+key.exchange()+", kind="+(key.book()?"BOOK":"SPOT")
                    +", sharedSource="+(shared==null?null:key.book()?shared.getBookSource():shared.getLtpSource())+", sharedExchange="+(shared==null?null:key.book()?shared.getBookExchange():shared.getLtpExchange())
                    +", sharedIdentity="+(shared==null?null:key.book()?shared.getBookProviderIdentity():shared.getLtpProviderIdentity())+", sharedReceivedAt="+(shared==null?null:key.book()?shared.getLastBookAt():shared.getLastLtpAt())
                    +", APIreceivedAt="+attempt.at()+", APIproviderAt="+attempt.providerAt();
            audit.record(TradeAuditEventEntity.builder().strategyId(AdxDmiRules.TEMPLATE).source(AdxDmiRules.SOURCE)
                    .symbol(script==null?null:script.getTradingSymbol()).eventType("QUOTE_GATE").outcome("SHOONYA_VERIFIED".equals(reason)?"ACCEPTED":"WAITING")
                    .reason(reason).details(detail).occurredAt(LocalDateTime.now(clock)).build());
            return new Notice(now,reason);
        });
    }
}
