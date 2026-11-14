package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.util.TradeSide;
import org.json.JSONArray;
import org.springframework.stereotype.Service;
import java.util.*;

/** Persisted Simulator price policies: fresh LTP for new baskets, separate reviewed legacy market retry. */
@Service
public class BatmanMarketEntry {
    private final BatmanBasketRepository baskets;
    private final BrokerCredentialsRepository credentials;
    private final BatmanBooks books;
    public BatmanMarketEntry(BatmanBasketRepository baskets,BrokerCredentialsRepository credentials,BatmanBooks books){this.baskets=baskets;this.credentials=credentials;this.books=books;}
    /** Persisted policy applies to marks/exits as well as entry; old baskets keep their original policy. */
    public java.util.Optional<org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot> freshQuote(TriggeredTradeSetupEntity trade){
        if(trade==null||!BatmanRules.owns(trade.getSource())||trade.getStrategyRunId()==null)return java.util.Optional.empty();
        var b=baskets.findByStrategyRunId(trade.getStrategyRunId()).orElse(null);
        if(b==null||!Objects.equals(b.getAppUserId(),trade.getAppUserId())||!Objects.equals(b.getBrokerCredentialsId(),trade.getBrokerCredentialsId())||!Objects.equals(b.getSymbol(),trade.getSymbol()))return java.util.Optional.empty();
        var plan=new JSONArray(b.getPlanJson());
        for(int i=0;i<plan.length();i++){
            var leg=plan.getJSONObject(i);
            if(!leg.optBoolean("freshLtpEntry")||!(BatmanRules.PREFIX+b.getId()+":"+i).equals(trade.getSource()))continue;
            verifyFreshLeg(trade,b,leg);
            var q=books.simulatorPrice(trade.getScripCode(),trade.getExchange(),TradeSide.isShort(trade),trade.getQuantity());
            if(!BatmanBooks.valid(q,java.time.Instant.now()))return java.util.Optional.empty();
            return java.util.Optional.of(q);
        }
        return java.util.Optional.empty();
    }
    private void verifyFreshLeg(TriggeredTradeSetupEntity trade,org.com.sharekhan.entity.BatmanBasket b,org.json.JSONObject leg){
        if(!Objects.equals(trade.getScripCode(),leg.getInt("code"))||!Objects.equals(trade.getExchange(),leg.getString("exchange"))
                ||trade.getQuantity()==null||(trade.getQuantity()<1||trade.getQuantity()>leg.getLong("quantity"))||!TradeSide.entry(trade).equals(leg.getString("action"))
                ||trade.getStrikePrice()==null||Double.compare(trade.getStrikePrice(),leg.getDouble("strike"))!=0||!Objects.equals(trade.getOptionType(),leg.getString("side"))
                ||!b.getExpiry().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")).equals(trade.getExpiry()))throw new IllegalArgumentException("Fresh LTP trade differs from persisted basket");
        if(credentials.findById(trade.getBrokerCredentialsId()).filter(c->"Simulator".equalsIgnoreCase(c.getBrokerName())&&Objects.equals(c.getAppUserId(),trade.getAppUserId())).isEmpty())throw new IllegalArgumentException("LTP-fill assumption is Simulator-only");
    }
    public OptionalDouble requestPrice(org.com.sharekhan.entity.TriggerTradeRequestEntity r){
        if(r==null)return OptionalDouble.empty();
        return price(TriggeredTradeSetupEntity.builder().strategyRunId(r.getStrategyRunId()).source(r.getSource()).appUserId(r.getAppUserId()).brokerCredentialsId(r.getBrokerCredentialsId())
                .symbol(r.getSymbol()).scripCode(r.getScripCode()).exchange(r.getExchange()).quantity(r.getQuantity()).entryAction(r.getEntryAction()).strikePrice(r.getStrikePrice()).optionType(r.getOptionType()).expiry(r.getExpiry()).build());
    }
    public OptionalDouble price(TriggeredTradeSetupEntity trade){
        if(trade==null||!BatmanRules.owns(trade.getSource())||trade.getStrategyRunId()==null)return OptionalDouble.empty();
        var b=baskets.findByStrategyRunId(trade.getStrategyRunId()).orElse(null);
        if(b==null||!"ENTERING".equals(b.getState())||!Objects.equals(b.getAppUserId(),trade.getAppUserId())||!Objects.equals(b.getBrokerCredentialsId(),trade.getBrokerCredentialsId())||!Objects.equals(b.getSymbol(),trade.getSymbol()))return OptionalDouble.empty();
        var plan=new JSONArray(b.getPlanJson());
        for(int i=0;i<plan.length();i++){
            var leg=plan.getJSONObject(i);
            boolean fresh=leg.optBoolean("freshLtpEntry",false);
            if(!(leg.optBoolean("marketEntry",false)||fresh)||!(BatmanRules.PREFIX+b.getId()+":"+i).equals(trade.getSource()))continue;
            if(!Objects.equals(trade.getScripCode(),leg.getInt("code"))||!Objects.equals(trade.getExchange(),leg.getString("exchange"))
                    ||trade.getQuantity()==null||trade.getQuantity()!=leg.getLong("quantity")||!TradeSide.entry(trade).equals(leg.getString("action"))
                    ||trade.getStrikePrice()==null||Double.compare(trade.getStrikePrice(),leg.getDouble("strike"))!=0||!Objects.equals(trade.getOptionType(),leg.getString("side"))
                    ||!b.getExpiry().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")).equals(trade.getExpiry()))throw new IllegalArgumentException("Market entry differs from the reviewed basket plan");
            if(credentials.findById(trade.getBrokerCredentialsId()).filter(c->"Simulator".equalsIgnoreCase(c.getBrokerName())&&Objects.equals(c.getAppUserId(),trade.getAppUserId())).isEmpty())throw new IllegalArgumentException("Market-fill assumption is Simulator-only");
            var q=fresh?books.simulatorPrice(trade.getScripCode(),trade.getExchange(),!TradeSide.isShort(trade),trade.getQuantity()):books.marketBook(trade.getScripCode(),trade.getExchange());
            if(fresh&&(q==null||!BatmanBooks.valid(q,java.time.Instant.now())))throw new org.com.sharekhan.exception.EntryNotReadyException("Waiting for a fresh exact-contract Batman option price; no order submitted");
            double price=q==null?leg.getDouble("admittedPremium"):TradeSide.isShort(trade)?q.getBestBid():q.getBestAsk();
            if(!Double.isFinite(price)||price<=0)throw new IllegalArgumentException("An observed positive option premium is required");
            return OptionalDouble.of(price);
        }
        return OptionalDouble.empty();
    }
}
