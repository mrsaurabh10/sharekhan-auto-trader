package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
import org.com.sharekhan.strategy.mcx.NaturalGasSession;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable exit intent lives on the trade; feed ownership is rebuilt from open positions after restart. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasScalpSessionCloseService {
    public static final String REASON="MCX_SESSION_CLOSE";
    public static final List<TriggeredTradeStatus> ACTIVE=List.of(TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,
            TriggeredTradeStatus.EXIT_ORDER_PLACED,TriggeredTradeStatus.EXIT_FAILED);
    private static final String WAIT="MCX session close awaiting a fresh exact option bid; position remains open";
    private static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private final TriggeredTradeSetupRepository trades;
    private final QuoteCacheService quotes;
    private final WebSocketSubscriptionService feeds;
    private final ExitChaseControlService controls;
    private final TradeAuditService audit;
    private final Set<Long> watched=ConcurrentHashMap.newKeySet();
    private final Set<Long> alerted=ConcurrentHashMap.newKeySet();

    public static boolean owns(TriggeredTradeSetupEntity t) {
        return t!=null && NaturalGasScalpingStrategy.SOURCE.equals(t.getSource())
                && "MX".equalsIgnoreCase(t.getExchange()) && Boolean.TRUE.equals(t.getIntraday())
                && t.getId()!=null && t.getScripCode()!=null && t.getEntryAt()!=null
                && t.getQuantity()!=null && t.getQuantity()>0 && t.getStatus()!=null && ACTIVE.contains(t.getStatus());
    }
    public static boolean due(TriggeredTradeSetupEntity t,LocalDateTime now) {
        return owns(t) && !now.isBefore(t.getEntryAt()) && (REASON.equals(t.getExitReason())
                || now.toLocalDate().isAfter(t.getEntryAt().toLocalDate())
                || !now.toLocalTime().isBefore(NaturalGasSession.SQUARE_OFF));
    }
    private static String owner(Long id){return "ngas-scalp-position:"+id;}
    public void retain(TriggeredTradeSetupEntity t,Instant now) {
        if(!owns(t))return;
        String key="MX"+t.getScripCode();
        if(feeds.retainFullFeed(owner(t.getId()),key))watched.add(t.getId());
        if(NaturalGasSession.marketOpen(LocalDateTime.ofInstant(now,ZONE))
                && !NaturalGasScalpProfitLockService.usableBook(t,quotes.getSnapshot(t.getScripCode()).orElse(null),now))
            feeds.refreshFullFeed(owner(t.getId()),key);
    }
    public void releaseFlat(Set<Long> active) {
        for(Long id:new HashSet<>(watched))if(!active.contains(id)) {
            // A query snapshot that omitted a row is not proof that its broker position is flat.
            var current=trades.findById(id).orElse(null);
            if(current!=null && current.getStatus()!=null
                    && Set.of(TriggeredTradeStatus.EXITED_SUCCESS,TriggeredTradeStatus.COMPLETED).contains(current.getStatus())
                    && (current.getReason()==null || (!current.getReason().contains("RECONCILIATION_REQUIRED")
                    && !current.getReason().contains("UNKNOWN") && !current.getReason().contains("UNCONFIRMED")))) {
                feeds.releaseFullFeed(owner(id));watched.remove(id);alerted.remove(id);
            }
        }
    }
    public record Exit(TriggeredTradeSetupEntity trade,double bid) { }
    /** Never substitutes LTP/entry/last night's cached price for a fresh sell-side book. */
    public Optional<Exit> prepare(Long id,LocalDateTime now) {
        var trade=trades.findById(id).orElse(null);
        if(!due(trade,now))return Optional.empty();
        // The committed claim survives a missing book, adapter exception, process restart and next-day ticks.
        trades.latchNaturalGasSessionClose(id,now);
        trade=trades.findById(id).orElse(null);
        if(!owns(trade)||!REASON.equals(trade.getExitReason()))return Optional.empty();
        Instant instant=now.atZone(ZONE).toInstant();
        retain(trade,instant);
        var q=quotes.getSnapshot(trade.getScripCode()).orElse(null);
        if(!NaturalGasSession.marketOpen(now)||!NaturalGasScalpProfitLockService.usableBook(trade,q,instant)) {
            if(!alerted.contains(id)) {
                controls.quoteAttention(trade,WAIT,instant);
                audit.record(TradeAuditEventEntity.builder().tradeId(id).appUserId(trade.getAppUserId())
                        .source(trade.getSource()).symbol(trade.getSymbol()).eventType("EXIT_NEEDS_ATTENTION")
                        .outcome("PENDING").reason(WAIT).details("intent="+REASON+", bookAt="+(q==null?null:q.getLastBookAt())).build());
                alerted.add(id);
                log.warn("MCX_SCALP_SESSION_CLOSE_WAIT | trade={} | bookAt={} | position remains open",id,q==null?null:q.getLastBookAt());
            }
            return Optional.empty();
        }
        alerted.remove(id);
        return Optional.of(new Exit(trade,q.getBestBid()));
    }
}
