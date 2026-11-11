package org.com.sharekhan.strategy.adxdmi;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Persist observations/exit intent, then let the caller use normal engine square-off. No broker RPC here. */
@Service @RequiredArgsConstructor
public class AdxDmiIndexExitPolicy {
    private final EntityManager em;
    private final TriggeredTradeSetupRepository trades;
    @Transactional
    public String observe(Long tradeId,Double spot,LocalDateTime now) {
        var trade=em.find(TriggeredTradeSetupEntity.class,tradeId);
        if (!AdxDmiIndexExitRules.owns(trade) || trade.getStrategyRunId()==null) return null;
        // Same account lock as admission: serialize creation and observations across all three requests.
        em.find(BrokerCredentialsEntity.class,trade.getBrokerCredentialsId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(trade,LockModeType.PESSIMISTIC_WRITE);
        if (!List.of(TriggeredTradeStatus.EXECUTED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED).contains(trade.getStatus())) return null;
        String existing=trade.getExitReason();
        if (existing!=null && !existing.isBlank()) return existing;
        String signal=AdxDmiEntryAdmission.field(trade.getComment(),"ADX_SIGNAL");
        var group=trades.findByStrategyRunId(trade.getStrategyRunId()).stream()
                .filter(AdxDmiIndexExitRules::owns)
                .filter(t->Objects.equals(t.getAppUserId(),trade.getAppUserId()) && Objects.equals(t.getBrokerCredentialsId(),trade.getBrokerCredentialsId())
                        && Objects.equals(t.getSymbol(),trade.getSymbol()) && signal.equals(AdxDmiEntryAdmission.field(t.getComment(),"ADX_SIGNAL"))).toList();
        LocalDateTime start=group.stream().map(TriggeredTradeSetupEntity::getEntryAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        if (start==null) return null; // Never start a fresh 15-minute clock after restart or before a confirmed fill.
        String key=identity(trade,signal);
        var state=em.find(AdxDmiExitState.class,key,LockModeType.PESSIMISTIC_WRITE);
        if (state==null) {state=new AdxDmiExitState();state.setId(key);state.setStartedAt(start);em.persist(state);}
        double t1=Double.parseDouble(AdxDmiEntryAdmission.field(trade.getComment(),"ADX_INDEX_T1"));
        var next=AdxDmiIndexExitRules.observe(new AdxDmiIndexExitRules.State(state.getStartedAt(),state.getT1ReachedAt(),state.getTimedOutAt()),
                "PE".equalsIgnoreCase(trade.getOptionType()),t1,spot,now);
        state.setT1ReachedAt(next.t1ReachedAt());state.setTimedOutAt(next.timedOutAt());
        String reason=AdxDmiIndexExitRules.exit(next,"PE".equalsIgnoreCase(trade.getOptionType()),trade.getStopLoss(),trade.getTarget1(),spot,now);
        // Persist the index event even if the option quote is unavailable or price subsequently recovers.
        if (reason!=null) {trade.setExitReason(reason);em.flush();}
        return reason;
    }
    static String identity(TriggeredTradeSetupEntity trade,String signal) {
        String text=trade.getAppUserId()+"|"+trade.getBrokerCredentialsId()+"|"+trade.getSymbol()+"|"+signal;
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
