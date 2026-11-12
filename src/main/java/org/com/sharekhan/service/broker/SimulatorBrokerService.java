package org.com.sharekhan.service.broker;

import org.com.sharekhan.util.TradeSide;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SimulatorBrokerService implements ModifiableEntryBrokerService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.com.sharekhan.cache.QuoteCacheService quotes;

    @org.springframework.beans.factory.annotation.Autowired
    private org.com.sharekhan.strategy.adxdmi.AdxDmiMarketData adxQuotes;
    private final java.util.Map<String,Double> adxConsumed=new java.util.HashMap<>();
    private synchronized double adxFill(TriggeredTradeSetupEntity trade,double fallback,boolean buy){
        if(!org.com.sharekhan.strategy.adxdmi.AdxDmiRules.premium(trade.getSource()))return fallback;
        var q=adxQuotes==null?null:adxQuotes.book(trade.getScripCode(),trade.getExchange());
        boolean valid=adxQuotes!=null && adxQuotes.freshBook(trade.getExchange(),q,java.time.Instant.now(),trade.getScripCode());
        if(!valid){
            if(buy)throw new org.com.sharekhan.exception.EntryNotReadyException("ADX simulator waiting for fresh exact option book");
            throw new IllegalStateException("ADX simulator exit waiting for fresh exact option bid");
        }
        if(buy && (q.getBestAsk()>trade.getEntryPrice()+1e-7 || (premiumStop(trade) && trade.getStopLoss()!=null && q.getBestAsk()<=trade.getStopLoss()) || (q.getAskQuantity()==null || q.getAskQuantity()<trade.getQuantity())))
            throw new org.com.sharekhan.exception.EntryNotReadyException("ADX ask moved outside frozen bracket or displayed depth unavailable");
        if(!buy && (q.getBidQuantity()==null || q.getBidQuantity()<trade.getQuantity()))throw new IllegalStateException("ADX bid depth unavailable; exit remains pending");
        String key=trade.getExchange()+":"+trade.getScripCode()+":"+q.getBookProviderIdentity()+":"+q.getLastBookAt()+":"+buy;
        double used=adxConsumed.getOrDefault(key,0d),available=buy?q.getAskQuantity():q.getBidQuantity();
        if(available-used<trade.getQuantity()){
            if(buy)throw new org.com.sharekhan.exception.EntryNotReadyException("ADX displayed ask depth already consumed");
            throw new IllegalStateException("ADX displayed bid depth already consumed; await a new book");
        }
        if(adxConsumed.size()>2000)adxConsumed.clear();
        adxConsumed.put(key,used+trade.getQuantity());
        return buy?q.getBestAsk():q.getBestBid();
    }

    private double naturalGasFill(TriggeredTradeSetupEntity trade, double fallback, boolean buy) {
        if (!org.com.sharekhan.strategy.mcx.NaturalGasSession.owns(trade.getSource())) return fallback;
        var q = quotes == null ? null : quotes.getSnapshot(trade.getScripCode()).orElse(null);
        var now = java.time.Instant.now();
        if (q == null || !q.hasBook() || !q.isCoherentBook() || !Double.isFinite(q.getBestBid()) || !Double.isFinite(q.getBestAsk())
                || q.getBestBid() > q.getBestAsk() || q.getLastBookAt() == null || q.getLastBookAt().isAfter(now)
                || q.getLastBookAt().isBefore(now.minusSeconds(15)) || !"SHAREKHAN_WS".equals(q.getBookSource())
                || !("scrip:" + trade.getScripCode()).equals(q.getBookProviderIdentity())) {
            if (buy) throw new org.com.sharekhan.exception.EntryNotReadyException("Simulator waiting for a fresh MCX option book");
            throw new IllegalStateException("MCX simulator exit waiting for a fresh option bid; position remains open");
        }
        if (buy && q.getBestAsk() > fallback + 0.0000001) throw new org.com.sharekhan.exception.EntryNotReadyException("MCX ask moved above the simulator entry limit");
        if(buy && org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.SOURCE.equalsIgnoreCase(trade.getSource())
                && (trade.getEntryPrice()==null || !Double.isFinite(trade.getEntryPrice()) || q.getBestAsk()>trade.getEntryPrice()+0.0000001
                    || q.getLastBookAt().isBefore(now.minusSeconds(5)) || q.getBestBid()<=0
                    || (q.getBestAsk()-q.getBestBid())/q.getBestAsk()>.02))
            throw new org.com.sharekhan.exception.EntryNotReadyException("Averaging ask moved or book aged beyond its admitted basket budget");
        return buy ? q.getBestAsk() : q.getBestBid();
    }


    @Override
    public Broker getBroker() {
        return Broker.SIMULATOR;
    }

    @Override
    public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp) {
        ltp = adxFill(trade,naturalGasFill(trade, ltp, true),true);
        log.info("🚀 [SIMULATOR] Placing {} entry order for {} at {}", TradeSide.entry(trade), trade.getSymbol(), ltp);
        String orderId = "SIM-" + java.util.UUID.randomUUID();
        
        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status("Fully Executed")
                .attemptedPrice(ltp)
                .executedPrice(ltp)
                .build();
    }

    @Override
    public OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade,
                                                 BrokerContext context,
                                                 String orderId,
                                                 double newPrice) {
        newPrice = adxFill(trade,naturalGasFill(trade, newPrice, true),true);
        log.info("🔁 [SIMULATOR] Modifying BUY order {} for {} to {}", orderId, trade.getSymbol(), newPrice);
        String effectiveOrderId = orderId != null && !orderId.isBlank()
                ? orderId
                : "SIM-" + java.util.UUID.randomUUID();

        return OrderPlacementResult.builder()
                .success(true)
                .orderId(effectiveOrderId)
                .status("Fully Executed")
                .attemptedPrice(newPrice)
                .executedPrice(newPrice)
                .build();
    }

    @Override
    public void cancelEntryOrder(TriggeredTradeSetupEntity trade,
                                 BrokerContext context,
                                 String orderId) {
        log.info("🚫 [SIMULATOR] Cancelled BUY order {} for {}", orderId, trade.getSymbol());
    }

    @Override
    public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice) {
        exitPrice = adxFill(trade,naturalGasFill(trade, exitPrice, false),false);
        log.info("🚀 [SIMULATOR] Placing {} exit order for {} at {}", TradeSide.exit(trade), trade.getSymbol(), exitPrice);
        String orderId = "SIM-EXIT-" + java.util.UUID.randomUUID();
        
        Double pnl = null;
        Double entryPriceForPnl = resolveEntryPriceForPnl(trade);
        if (entryPriceForPnl != null && trade.getQuantity() != null) {
            pnl = TradeSide.sign(trade) * (exitPrice - entryPriceForPnl) * trade.getQuantity();
        }

        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status("Fully Executed")
                .attemptedPrice(exitPrice)
                .executedPrice(exitPrice)
                .pnl(pnl)
                .build();
    }

    private Double resolveEntryPriceForPnl(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getActualEntryPrice() != null) {
            return trade.getActualEntryPrice();
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForEntry())
                || (trade.getUseSpotForEntry()==null && Boolean.TRUE.equals(trade.getUseSpotPrice()))) {
            log.warn("[SIMULATOR] Cannot compute PnL for spot-referenced trade {} because actualEntryPrice is missing. entryPrice={} is a reference price.",
                    trade.getId(), trade.getEntryPrice());
            return null;
        }
        return trade.getEntryPrice();
    }

    private boolean premiumStop(TriggeredTradeSetupEntity trade) {
        return trade.getUseSpotForSl()!=null ? !trade.getUseSpotForSl() : !Boolean.TRUE.equals(trade.getUseSpotPrice());
    }
}
