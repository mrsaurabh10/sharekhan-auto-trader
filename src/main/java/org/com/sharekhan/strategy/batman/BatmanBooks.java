package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ShoonyaQuoteService;
import org.com.sharekhan.service.MStockDepthService;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;

/** Exact-contract books; failed price/depth checks try the next independent provider. */
@Service
public class BatmanBooks {
    private final QuoteCacheService cache;
    private final ScriptMasterRepository scripts;
    private final ShoonyaQuoteService shoonya;
    private final WebSocketSubscriptionService feeds;
    private final MStockDepthService mstock;
    private record Issued(Instant attempted,Instant providerAt,QuoteCacheService.QuoteSnapshot snapshot){}
    private final Map<Integer,Issued> issued=new HashMap<>();
    private final Map<String,Instant> leases=new HashMap<>();
    private final Map<Integer,String> reasons=new HashMap<>();
    public BatmanBooks(QuoteCacheService cache,ScriptMasterRepository scripts,ShoonyaQuoteService shoonya,WebSocketSubscriptionService feeds,MStockDepthService mstock){
        this.cache=cache;this.scripts=scripts;this.shoonya=shoonya;this.feeds=feeds;this.mstock=mstock;
    }
    /** Prepare all legs before reading any; subsequent scheduled attempts see warmed full feeds. */
    public synchronized void prepare(List<ScriptMasterEntity> contracts){for(var script:contracts)watch(script);}
    private void watch(ScriptMasterEntity script){
        if(script==null||script.getScripCode()==null)return;
        String key=script.getExchange()+script.getScripCode();
        if(!leases.containsKey(key)){feeds.subscribeToScrip(key);leases.put(key,Instant.now());}
        else leases.put(key,Instant.now());
        mstock.watch(script);
    }
    public synchronized String reason(int code){return reasons.getOrDefault(code,"No usable exact option book");}
    public synchronized QuoteCacheService.QuoteSnapshot book(int code,String exchange){return book(code,exchange,false,0);}
    public synchronized QuoteCacheService.QuoteSnapshot book(int code,String exchange,boolean buy,long units){
        var script=scripts.findByScripCode(code);
        if(script==null||!exchange.equals(script.getExchange())){reasons.put(code,"Execution contract unavailable");return null;}
        watch(script);var now=Instant.now();var shared=cache.getSharekhanSnapshot(code).orElse(null);
        if(shared!=null&&"SHAREKHAN_WS".equals(shared.getBookSource())&&exchange.equals(shared.getBookExchange())&&("scrip:"+code).equals(shared.getBookProviderIdentity())&&usable(shared,now,buy,units))return shared;
        var depth=mstock.book(code);
        if(depth!=null&&exchange.equals(depth.getBookExchange())&&usable(depth,now,buy,units))return depth;
        var old=issued.get(code);
        if(old==null||!old.attempted().plusSeconds(2).isAfter(now)){
            issued.put(code,new Issued(now,null,null));
            var exact=shoonya.getExactQuote(script).orElse(null);
            if(exact!=null){
                var q=exact.quote();now=Instant.now();String expected="NF".equals(exchange)?"NFO":"BF".equals(exchange)?"BFO":"";
                if(q!=null&&q.hasConfirmedIdentity()&&expected.equals(exact.exchange())&&recent(exact.providerUpdatedAt(),now)&&recent(exact.receivedAt(),now)){
                    var snapshot=QuoteCacheService.QuoteSnapshot.builder().scripCode(code).bookSource("SHOONYA").bookExchange(exchange)
                            .bookProviderIdentity(q.returnedToken()+":"+q.returnedTradingSymbol()).coherentBook(true)
                            .bestBid(q.bestBid()).bestAsk(q.bestAsk()).bidQuantity(q.bidQuantity()).askQuantity(q.askQuantity()).lastBookAt(exact.providerUpdatedAt()).updatedAt(exact.receivedAt()).build();
                    issued.put(code,new Issued(now,exact.providerUpdatedAt(),snapshot));
                }
            }
            old=issued.get(code);
        }
        if(old!=null&&recent(old.providerAt(),Instant.now())&&usable(old.snapshot(),Instant.now(),buy,units))return old.snapshot();
        // Asynchronous full-feed packets may have arrived during the REST request.
        shared=cache.getSharekhanSnapshot(code).orElse(null);depth=mstock.book(code);now=Instant.now();
        if(shared!=null&&"SHAREKHAN_WS".equals(shared.getBookSource())&&exchange.equals(shared.getBookExchange())&&("scrip:"+code).equals(shared.getBookProviderIdentity())&&usable(shared,now,buy,units))return shared;
        if(depth!=null&&exchange.equals(depth.getBookExchange())&&usable(depth,now,buy,units))return depth;
        reasons.put(code,"No provider passed exact identity, 5-second freshness, spread <=5% and "+(buy?"ask":"bid")+" quantity >="+units+"; "+mstock.availability());
        return null;
    }
    public synchronized List<Map<String,Object>> diagnostics(){
        List<Map<String,Object>> result=new ArrayList<>();
        for(String key:leases.keySet()){
            int code=Integer.parseInt(key.substring(2));var row=new LinkedHashMap<String,Object>();
            row.put("key",key);row.put("sharekhan",cache.getSharekhanSnapshot(code).orElse(null));
            row.put("latestCache",cache.getSnapshot(code).orElse(null));row.put("mstock",mstock.book(code));
            row.put("shoonya",issued.containsKey(code)?issued.get(code).snapshot():null);
            row.put("reason",reasons.get(code));row.put("mstockAvailability",mstock.availability());result.add(row);
        }
        return result;
    }
    static boolean usable(QuoteCacheService.QuoteSnapshot q,Instant now,boolean buy,long units){
        return valid(q,now)&&(q.getBestAsk()-q.getBestBid())/q.getBestAsk()<=.05&&(buy?q.getAskQuantity():q.getBidQuantity())>=units;
    }
    public static boolean valid(QuoteCacheService.QuoteSnapshot q,Instant now){return q!=null&&q.hasBook()&&q.isCoherentBook()&&Double.isFinite(q.getBestBid())&&Double.isFinite(q.getBestAsk())&&q.getBestBid()<=q.getBestAsk()&&q.getBidQuantity()!=null&&Double.isFinite(q.getBidQuantity())&&q.getBidQuantity()>=0&&q.getAskQuantity()!=null&&Double.isFinite(q.getAskQuantity())&&q.getAskQuantity()>=0&&recent(q.getLastBookAt(),now);}
    private static boolean recent(Instant t,Instant now){return t!=null&&!t.isAfter(now)&&!t.isBefore(now.minusSeconds(5));}
    @Scheduled(fixedDelay=10000) public synchronized void releaseIdle(){
        var cutoff=Instant.now().minusSeconds(60);
        for(var key:new ArrayList<>(leases.keySet()))if(leases.get(key).isBefore(cutoff)){feeds.unsubscribeFromScrip(key);leases.remove(key);}
        issued.entrySet().removeIf(e->e.getValue().attempted().isBefore(cutoff));
        reasons.keySet().removeIf(code->!issued.containsKey(code));
    }
    @PreDestroy public synchronized void close(){for(var key:leases.keySet())feeds.unsubscribeFromScrip(key);leases.clear();}
}
