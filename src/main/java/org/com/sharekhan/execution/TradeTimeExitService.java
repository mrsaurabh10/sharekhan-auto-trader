package org.com.sharekhan.execution;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Persist generic time-exit intent without doing broker/network work in the transaction. */
@Service @RequiredArgsConstructor
public class TradeTimeExitService {
    public static final String REASON="TIME_EXIT";
    private static final List<TriggeredTradeStatus> ACTIVE=List.of(TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED);
    private final EntityManager em;
    private final TriggeredTradeSetupRepository trades;
    private final LtpCacheService prices;

    @Transactional
    public boolean observeFromCache(Long id) {
        return observeCached(id,null);
    }
    @Transactional
    public boolean observeFromCache(Long id,LocalDateTime now) {
        return observeCached(id,now);
    }
    private boolean observeCached(Long id,LocalDateTime fixedNow) {
        var trade=em.find(TriggeredTradeSetupEntity.class,id);
        if(!configured(trade))return false;
        var c=trade.getExecutionSettings();
        Integer code=Boolean.TRUE.equals(c.getTimeExitUseSpot())?trade.getSpotScripCode():trade.getScripCode();
        var observation=code==null?null:prices.getPriceObservation(code);
        // Sample production time after the cache read; DB latency/new ticks must not make fresh observations appear future-dated.
        var now=fixedNow==null?LocalDateTime.now(ZoneId.of("Asia/Kolkata")):fixedNow;
        return observe(id,observation==null?null:observation.price(),observation==null?null:observation.observedAt(),now);
    }

    @Transactional
    public boolean observe(Long id,Double price,LocalDateTime observedAt,LocalDateTime now) {
        var trade=em.find(TriggeredTradeSetupEntity.class,id);
        if(!configured(trade))return false;
        // The normal execution account lock serializes first state creation and all group observations.
        if(trade.getBrokerCredentialsId()==null)throw new IllegalStateException("Timed trade requires an execution account");
        em.find(BrokerCredentialsEntity.class,trade.getBrokerCredentialsId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(trade,LockModeType.PESSIMISTIC_WRITE);
        if(!ACTIVE.contains(trade.getStatus()))return false;
        if(REASON.equals(trade.getExitReason())) {
            if(trade.getStatus()==TriggeredTradeStatus.EXIT_FAILED)trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
            return true;
        }
        var c=trade.getExecutionSettings();
        var group=trades.findByExecutionSettings_TimeExitGroupIdAndAppUserIdAndBrokerCredentialsId(
                c.getTimeExitGroupId(),trade.getAppUserId(),trade.getBrokerCredentialsId());
        if(group.isEmpty())return false;
        if(group.stream().anyMatch(t->!sameConfiguration(trade,t)))throw new IllegalStateException("Inconsistent time-exit group configuration");
        var start=group.stream().map(TriggeredTradeSetupEntity::getEntryAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        if(start==null || now.isBefore(start))return false;
        String key=identity(trade);
        var state=em.find(TradeTimeExitState.class,key,LockModeType.PESSIMISTIC_WRITE);
        if(state==null){state=new TradeTimeExitState();state.setId(key);state.setStartedAt(start);em.persist(state);}
        var deadline=state.getStartedAt().plusMinutes(c.getTimeExitMinutes());
        // Only a fresh observation from after the first fill and strictly before the deadline counts.
        if(state.getTimedOutAt()==null && state.getProgressReachedAt()==null && c.getTimeExitProgressPrice()!=null
                && price!=null && Double.isFinite(price) && price>0 && observedAt!=null
                && !observedAt.isBefore(state.getStartedAt()) && !observedAt.isAfter(now)
                && !observedAt.isBefore(now.minusSeconds(5)) && observedAt.isBefore(deadline)
                && (c.getTimeExitProgressDirection()==TradeExecutionSettings.Direction.ABOVE
                    ? price+1e-8>=c.getTimeExitProgressPrice() : price-1e-8<=c.getTimeExitProgressPrice()))
            state.setProgressReachedAt(observedAt);
        if(state.getProgressReachedAt()==null && !now.isBefore(deadline))state.setTimedOutAt(deadline);
        if(state.getTimedOutAt()==null)return false;
        // Claim every remaining leg atomically. Preserve SL/manual exits and any already placed sell.
        group.stream().sorted(Comparator.comparing(TriggeredTradeSetupEntity::getId)).forEach(t->{
            em.refresh(t,LockModeType.PESSIMISTIC_WRITE);
            if((t.getStatus()==TriggeredTradeStatus.EXECUTED || t.getStatus()==TriggeredTradeStatus.TARGET_ORDER_PLACED
                    || t.getStatus()==TriggeredTradeStatus.EXIT_FAILED) && (t.getExitReason()==null || t.getExitReason().isBlank())) {
                t.setExitReason(REASON);t.setExitClaimedAt(now);t.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
            }
        });
        em.flush();return REASON.equals(trade.getExitReason());
    }
    public static boolean configured(TriggeredTradeSetupEntity t) {
        return t!=null && t.getExecutionSettings()!=null && t.getExecutionSettings().getTimeExitMinutes()!=null;
    }
    private static boolean sameConfiguration(TriggeredTradeSetupEntity a,TriggeredTradeSetupEntity b) {
        var x=a.getExecutionSettings();var y=b.getExecutionSettings();
        return Objects.equals(a.getScripCode(),b.getScripCode()) && Objects.equals(a.getSpotScripCode(),b.getSpotScripCode())
                && Objects.equals(x.getTimeExitMinutes(),y.getTimeExitMinutes()) && Objects.equals(x.getTimeExitProgressPrice(),y.getTimeExitProgressPrice())
                && x.getTimeExitProgressDirection()==y.getTimeExitProgressDirection() && Objects.equals(x.getTimeExitUseSpot(),y.getTimeExitUseSpot());
    }
    static String identity(TriggeredTradeSetupEntity t) {
        String value=t.getAppUserId()+"|"+t.getBrokerCredentialsId()+"|"+t.getExecutionSettings().getTimeExitGroupId();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
