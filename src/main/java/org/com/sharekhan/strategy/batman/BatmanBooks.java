package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ShoonyaQuoteService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Exact-contract books, independently obtained from the chosen execution broker. */
@Service
public class BatmanBooks {
    private final QuoteCacheService cache;private final ScriptMasterRepository scripts;private final ShoonyaQuoteService shoonya;
    private record Issued(Instant attempted,Instant providerAt,QuoteCacheService.QuoteSnapshot snapshot){}
    private final Map<Integer,Issued> issued=new HashMap<>();
    public BatmanBooks(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya){this.cache=cache;this.scripts=scripts;this.shoonya=shoonya;}
    public synchronized QuoteCacheService.QuoteSnapshot book(int code,String exchange){
        var now=Instant.now();var shared=cache.getSnapshot(code).orElse(null);
        if(shared!=null&&valid(shared,now)&&"SHAREKHAN_WS".equals(shared.getBookSource())&&exchange.equals(shared.getBookExchange())&&("scrip:"+code).equals(shared.getBookProviderIdentity()))return shared;
        var old=issued.get(code);
        if(old!=null&&old.attempted().plusSeconds(2).isAfter(now))return recent(old.providerAt(),now)&&valid(old.snapshot(),now)?old.snapshot():null;
        issued.put(code,new Issued(now,null,null));
        var script=scripts.findByScripCode(code);if(script==null||!exchange.equals(script.getExchange()))return null;
        var exact=shoonya.getExactQuote(script).orElse(null);if(exact==null)return null;
        var q=exact.quote();now=Instant.now();String expected="NF".equals(exchange)?"NFO":"BF".equals(exchange)?"BFO":"";
        if(q==null||!q.hasConfirmedIdentity()||expected.isEmpty()||!expected.equals(exact.exchange())||!recent(exact.providerUpdatedAt(),now)||!recent(exact.receivedAt(),now))return null;
        var snapshot=QuoteCacheService.QuoteSnapshot.builder().scripCode(code).bookSource("SHOONYA").bookExchange(exchange)
                .bookProviderIdentity(q.returnedToken()+":"+q.returnedTradingSymbol()).coherentBook(true)
                .bestBid(q.bestBid()).bestAsk(q.bestAsk()).bidQuantity(q.bidQuantity()).askQuantity(q.askQuantity()).lastBookAt(exact.receivedAt()).updatedAt(exact.receivedAt()).build();
        if(!valid(snapshot,now))return null;
        issued.put(code,new Issued(now,exact.providerUpdatedAt(),snapshot));return snapshot;
    }
    public static boolean valid(QuoteCacheService.QuoteSnapshot q,Instant now){return q!=null&&q.hasBook()&&q.isCoherentBook()&&Double.isFinite(q.getBestBid())&&Double.isFinite(q.getBestAsk())&&q.getBestBid()<=q.getBestAsk()&&q.getBidQuantity()!=null&&Double.isFinite(q.getBidQuantity())&&q.getBidQuantity()>=0&&q.getAskQuantity()!=null&&Double.isFinite(q.getAskQuantity())&&q.getAskQuantity()>=0&&recent(q.getLastBookAt(),now);}
    private static boolean recent(Instant t,Instant now){return t!=null&&!t.isAfter(now)&&!t.isBefore(now.minusSeconds(5));}
}
