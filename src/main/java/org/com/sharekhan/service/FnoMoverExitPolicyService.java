package org.com.sharekhan.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.List;

/** Observes caches only. All broker work stays in PriceTriggerService's claimed exit workflow. */
@Service
@Slf4j
public class FnoMoverExitPolicyService {
    public static final String SOURCE = "strategy:FNO_0925_MOVER_ATR_BREAKOUT";
    public static final String VERSION = "FNO_EXIT_45M_V1";
    public static final String NO_PROGRESS = "FNO_NO_PROGRESS_45M";
    public static final String PROFIT_PROTECTION = "FNO_EARLY_PROFIT_PROTECTION";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_AGE = Duration.ofSeconds(5);
    private static final Duration MAX_SPOT_GAP = Duration.ofSeconds(15);
    private final JdbcTemplate jdbc;
    private final QuoteCacheService quotes;
    private final LtpCacheService prices;
    private final TriggerTradeRequestRepository requests;
    @Value("${app.strategy.fno-mover-exits.enabled:false}") private boolean enabled;

    public FnoMoverExitPolicyService(JdbcTemplate jdbc, QuoteCacheService quotes,
                                    LtpCacheService prices, TriggerTradeRequestRepository requests) {
        this.jdbc=jdbc;this.quotes=quotes;this.prices=prices;this.requests=requests;
    }
    @PostConstruct void configured() {
        log.info("FNO_MOVER_EXIT_POLICY | enabled={} | version={} | noProgressMinutes=45 | minimumProgressR=0.5 | armGain=10% | retainPeakGain=50%",
                enabled, VERSION);
    }
    public boolean enabled() { return enabled; }
    public static boolean owns(TriggeredTradeSetupEntity t) {
        return t!=null && SOURCE.equals(t.getSource()) && Boolean.TRUE.equals(t.getIntraday())
                && ("CE".equals(t.getOptionType()) || "PE".equals(t.getOptionType()))
                && !"SELL".equalsIgnoreCase(t.getEntryAction());
    }
    public static boolean isExitReason(String reason) {
        return NO_PROGRESS.equals(reason) || PROFIT_PROTECTION.equals(reason);
    }
    public record Decision(String reason, Double bid) {
        public static Decision waitForData() { return new Decision(null,null); }
        public boolean exit() { return reason!=null && bid!=null; }
    }

    /** Independent commit preserves a peak even if a competing worker wins the trade's exit claim. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Decision evaluate(TriggeredTradeSetupEntity t, Instant instant) {
        if (!enabled || !owns(t) || t.getId()==null || t.getEntryAt()==null
                || t.getScripCode()==null || t.getSpotScripCode()==null
                || t.getScripCode().equals(t.getSpotScripCode()) || !positive(t.getActualEntryPrice())
                || t.getQuantity()==null || t.getQuantity()<=0 || t.getLots()==null || t.getLots()<=0) return Decision.waitForData();
        LocalDateTime now=LocalDateTime.ofInstant(instant,ZONE);
        if (!now.toLocalDate().equals(t.getEntryAt().toLocalDate()) || now.isBefore(t.getEntryAt())
                || now.toLocalTime().isBefore(LocalTime.of(9,15)) || !now.toLocalTime().isBefore(LocalTime.of(15,30))) return Decision.waitForData();
        State state=load(t.getId());
        if(state==null) {
            state=initial(t,now);
            jdbc.update("INSERT INTO fno_mover_exit_state (trade_id,policy_version,entry_at,spot_entry,initial_spot_stop,initial_premium,peak_bid,reached_half_risk,progress_coverage_valid,last_spot_at,last_evaluated_at,protection_armed,protection_floor,last_message) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    t.getId(),VERSION,Timestamp.valueOf(state.entryAt),state.spotEntry,state.stop,state.premium,state.peak,
                    false,state.coverage,null,Timestamp.valueOf(now),false,0d,"INITIALIZED");
            state=load(t.getId());
        }
        if(state==null || !VERSION.equals(state.version) || !state.entryAt.equals(t.getEntryAt())
                || Double.compare(state.premium,t.getActualEntryPrice())!=0) return Decision.waitForData();

        // The latest receipt time is used once, not repeatedly refreshed by the timer.
        Double spot=prices.getLtp(t.getSpotScripCode());
        LocalDateTime spotAt=prices.getObservedAt(t.getSpotScripCode());
        boolean freshSpot=positive(spot) && spotAt!=null && !spotAt.isBefore(state.entryAt)
                && fresh(spotAt.atZone(ZONE).toInstant(),instant,MAX_AGE);
        if(state.lastSpotAt!=null && Duration.between(state.lastSpotAt,now).compareTo(MAX_SPOT_GAP)>0) state.coverage=false;
        if(freshSpot && (state.lastSpotAt==null || spotAt.isAfter(state.lastSpotAt))) {
            LocalDateTime previous=state.lastSpotAt==null?state.entryAt:state.lastSpotAt;
            if(Duration.between(previous,spotAt).compareTo(MAX_SPOT_GAP)>0) state.coverage=false;
            double risk=Math.abs(state.spotEntry-state.stop);
            double progress="PE".equals(t.getOptionType())?state.spotEntry-spot:spot-state.spotEntry;
            if(risk>0 && progress+1e-9>=risk*.5) state.reached=true;
            state.lastSpotAt=spotAt;
        }
        QuoteCacheService.QuoteSnapshot book=quotes.getSnapshot(t.getScripCode()).orElse(null);
        boolean executable=usableBook(t,book,instant);
        Decision decision=Decision.waitForData();
        if(executable) {
            double bid=book.getBestBid();
            state.peak=Math.max(state.peak,bid);
            // Net-profit floor uses the application's actual option-charge estimator.
            Double netFloor=TradeCostCalculator.minimumProfitableExitPrice(t,.01);
            if(netFloor!=null && state.peak+1e-9>=state.premium*1.10 && state.peak+1e-9>=netFloor) state.armed=true;
            double ratchet=state.premium+.5*(state.peak-state.premium);
            if(state.armed && netFloor!=null) state.floor=Math.max(state.floor,Math.max(netFloor,ratchet));
            if(state.armed && state.floor>0 && bid<=state.floor+1e-9) decision=new Decision(PROFIT_PROTECTION,bid);
            else if(freshSpot && state.coverage && !state.reached && !now.isBefore(state.entryAt.plusMinutes(45)))
                decision=new Decision(NO_PROGRESS,bid);
        }
        String message=decision.exit()?decision.reason():!state.coverage?"NO_PROGRESS_DISABLED_OBSERVATION_GAP"
                :!executable?"WAITING_FRESH_EXACT_OPTION_BOOK":state.reached?"PROGRESS_REACHED":state.armed?"PROFIT_PROTECTION_ARMED":"OBSERVING";
        jdbc.update("UPDATE fno_mover_exit_state SET peak_bid=?,reached_half_risk=?,progress_coverage_valid=?,last_spot_at=?,last_evaluated_at=?,protection_armed=?,protection_floor=?,last_message=? WHERE trade_id=?",
                state.peak,state.reached,state.coverage,state.lastSpotAt==null?null:Timestamp.valueOf(state.lastSpotAt),
                Timestamp.valueOf(now),state.armed,state.floor,message,t.getId());
        if(decision.exit()) log.info("FNO_MOVER_EXIT_DECISION | tradeId={} | reason={} | bid={} | peakBid={} | floor={} | reachedHalfRisk={} | entryAt={}",
                t.getId(),decision.reason(),decision.bid(),state.peak,state.floor,state.reached,state.entryAt);
        return decision;
    }

    /** Existing partial-booking creates a new position row; carry the same peak and progress forward. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void inherit(TriggeredTradeSetupEntity from,TriggeredTradeSetupEntity remaining) {
        if(!enabled || !owns(from) || !owns(remaining) || from.getId()==null || remaining.getId()==null
                || !java.util.Objects.equals(from.getAppUserId(),remaining.getAppUserId())
                || !java.util.Objects.equals(from.getBrokerCredentialsId(),remaining.getBrokerCredentialsId())
                || !java.util.Objects.equals(from.getScripCode(),remaining.getScripCode())
                || !java.util.Objects.equals(from.getEntryAt(),remaining.getEntryAt())
                || !java.util.Objects.equals(from.getActualEntryPrice(),remaining.getActualEntryPrice())) return;
        jdbc.update("INSERT INTO fno_mover_exit_state (trade_id,policy_version,entry_at,spot_entry,initial_spot_stop,initial_premium,peak_bid,reached_half_risk,progress_coverage_valid,last_spot_at,last_evaluated_at,protection_armed,protection_floor,last_message) SELECT ?,policy_version,entry_at,spot_entry,initial_spot_stop,initial_premium,peak_bid,reached_half_risk,progress_coverage_valid,last_spot_at,last_evaluated_at,protection_armed,protection_floor,'INHERITED_PARTIAL_POSITION' FROM fno_mover_exit_state WHERE trade_id=? ON CONFLICT DO NOTHING",
                remaining.getId(),from.getId());
    }

    public Double currentExitBid(TriggeredTradeSetupEntity trade,Instant now) {
        var book=quotes.getSnapshot(trade.getScripCode()).orElse(null);
        return usableBook(trade,book,now)?book.getBestBid():null;
    }
    private State initial(TriggeredTradeSetupEntity t,LocalDateTime now) {
        State s=new State();s.version=VERSION;s.entryAt=t.getEntryAt();s.premium=t.getActualEntryPrice();s.peak=s.premium;
        s.spotEntry=positive(t.getEntryPrice())?t.getEntryPrice():0;s.stop=positive(t.getStopLoss())?t.getStopLoss():0;
        boolean originalRisk=Boolean.TRUE.equals(t.getUseSpotForSl());
        if(t.getTriggerRequestId()!=null) {
            var request=requests.findById(t.getTriggerRequestId()).orElse(null);
            if(request!=null && SOURCE.equals(request.getSource()) && java.util.Objects.equals(request.getAppUserId(),t.getAppUserId())
                    && java.util.Objects.equals(request.getBrokerCredentialsId(),t.getBrokerCredentialsId())
                    && java.util.Objects.equals(request.getScripCode(),t.getScripCode())
                    && positive(request.getEntryPrice()) && positive(request.getStopLoss())) {
                s.spotEntry=request.getEntryPrice();s.stop=request.getStopLoss();originalRisk=true;
            }
        }
        boolean correctSide="PE".equals(t.getOptionType())?s.stop>s.spotEntry:s.stop<s.spotEntry;
        // Existing positions adopted late or unexplained gaps cannot prove "never progressed".
        s.coverage=originalRisk && s.spotEntry>0 && s.stop>0 && correctSide
                && Duration.between(s.entryAt,now).compareTo(MAX_SPOT_GAP)<=0;
        return s;
    }
    private State load(Long id) {
        List<State> states=jdbc.query("SELECT * FROM fno_mover_exit_state WHERE trade_id=? FOR UPDATE",(r,n)->{
            State s=new State();s.version=r.getString("policy_version");s.entryAt=r.getTimestamp("entry_at").toLocalDateTime();
            s.spotEntry=r.getDouble("spot_entry");s.stop=r.getDouble("initial_spot_stop");s.premium=r.getDouble("initial_premium");
            s.peak=r.getDouble("peak_bid");s.reached=r.getBoolean("reached_half_risk");s.coverage=r.getBoolean("progress_coverage_valid");
            var last=r.getTimestamp("last_spot_at");s.lastSpotAt=last==null?null:last.toLocalDateTime();
            s.armed=r.getBoolean("protection_armed");s.floor=r.getDouble("protection_floor");return s;
        },id);
        return states.isEmpty()?null:states.get(0);
    }
    static boolean usableBook(TriggeredTradeSetupEntity t,QuoteCacheService.QuoteSnapshot b,Instant now) {
        if(b==null || !b.isCoherentBook() || !positive(b.getBestBid()) || !positive(b.getBestAsk())
                || b.getBestBid()>b.getBestAsk() || !fresh(b.getLastBookAt(),now,MAX_AGE)
                || !"SHOONYA".equals(b.getBookSource()) || !java.util.Objects.equals(t.getExchange(),b.getBookExchange())
                || b.getBookProviderIdentity()==null || !b.getBookProviderIdentity().startsWith(t.getScripCode()+":")) return false;
        return b.getBidQuantity()!=null && Double.isFinite(b.getBidQuantity()) && b.getBidQuantity()>=t.getQuantity();
    }
    private static boolean fresh(Instant observed,Instant now,Duration age) {
        return observed!=null && !observed.isAfter(now) && !observed.plus(age).isBefore(now);
    }
    private static boolean positive(Double value) { return value!=null && Double.isFinite(value) && value>0; }
    static class State {
        String version;LocalDateTime entryAt,lastSpotAt;
        double spotEntry,stop,premium,peak,floor;
        boolean coverage,reached,armed;
    }
}
