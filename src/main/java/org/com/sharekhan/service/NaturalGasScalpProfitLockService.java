package org.com.sharekhan.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
import org.com.sharekhan.strategy.mcx.NaturalGasSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.Objects;

/** Simulator-only bid trail. Uses the same unverified-depth feed assumption as Scalping entries. */
@Service
@Slf4j
public class NaturalGasScalpProfitLockService {
    public static final String REASON = "NGAS_SCALP_PROFIT_LOCK";
    static final String VERSION = "NGAS_SCALP_LOCK_V1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;
    private final QuoteCacheService quotes;
    private final BrokerCredentialsRepository brokers;
    @Value("${app.strategy.ngas-scalp-profit-lock.enabled:false}") private boolean enabled;

    public NaturalGasScalpProfitLockService(JdbcTemplate jdbc, QuoteCacheService quotes, BrokerCredentialsRepository brokers) {
        this.jdbc = jdbc; this.quotes = quotes; this.brokers = brokers;
    }
    @PostConstruct void configured() {
        log.info("NGAS_SCALP_PROFIT_LOCK | enabled={} | version={} | broker=SIMULATOR | armGain=5% | retainPeakGain=50% | tick=0.05 | depth=UNVERIFIED", enabled, VERSION);
    }
    public boolean enabled() { return enabled; }
    public static boolean owns(TriggeredTradeSetupEntity t) {
        return t != null && NaturalGasScalpingStrategy.SOURCE.equals(t.getSource())
                && "MX".equalsIgnoreCase(t.getExchange()) && "NATURALGAS".equalsIgnoreCase(t.getSymbol())
                && Boolean.TRUE.equals(t.getIntraday()) && "BUY".equalsIgnoreCase(t.getEntryAction())
                && ("CE".equals(t.getOptionType()) || "PE".equals(t.getOptionType()))
                && Integer.valueOf(1).equals(t.getLots()) && Long.valueOf(1250).equals(t.getQuantity());
    }
    private boolean simulator(TriggeredTradeSetupEntity t) {
        return owns(t) && t.getBrokerCredentialsId() != null && t.getAppUserId() != null
                && brokers.findById(t.getBrokerCredentialsId()).filter(b ->
                "SIMULATOR".equalsIgnoreCase(b.getBrokerName()) && Boolean.TRUE.equals(b.getActive())
                        && Objects.equals(b.getAppUserId(), t.getAppUserId())).isPresent();
    }
    public record Decision(Double bid) {
        public static Decision waitForData() { return new Decision(null); }
        public boolean exit() { return bid != null; }
    }
    /** Commit peaks independently before the existing atomic exit claim. No orders are placed here. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision evaluate(TriggeredTradeSetupEntity t, Instant now) {
        if (!enabled || !simulator(t) || t.getId() == null || t.getScripCode() == null || t.getEntryAt() == null
                || !positive(t.getActualEntryPrice())) return Decision.waitForData();
        LocalDateTime at = LocalDateTime.ofInstant(now, ZONE);
        if (!NaturalGasSession.marketOpen(at) || !at.toLocalDate().equals(t.getEntryAt().toLocalDate())
                || at.isBefore(t.getEntryAt())) return Decision.waitForData();
        var book = quotes.getSnapshot(t.getScripCode()).orElse(null);
        if (!usableBook(t, book, now)) return Decision.waitForData();
        double bid = book.getBestBid();
        jdbc.update("INSERT INTO ngas_scalp_profit_lock_state (trade_id,scrip_code,app_user_id,broker_credentials_id,policy_version,entry_at,initial_premium,peak_bid,protection_floor,protection_armed,last_evaluated_at,last_message) VALUES (?,?,?,?,?,?,?,?,0,false,?,'INITIALIZED') ON CONFLICT DO NOTHING",
                t.getId(), t.getScripCode(), t.getAppUserId(), t.getBrokerCredentialsId(), VERSION, Timestamp.valueOf(t.getEntryAt()), t.getActualEntryPrice(), t.getActualEntryPrice(), Timestamp.valueOf(at));
        var states = jdbc.query("SELECT * FROM ngas_scalp_profit_lock_state WHERE trade_id=? FOR UPDATE", (r,n) ->
                new State(r.getInt("scrip_code"),r.getLong("app_user_id"),r.getLong("broker_credentials_id"),r.getString("policy_version"),r.getTimestamp("entry_at").toLocalDateTime(),r.getDouble("initial_premium"),
                        r.getDouble("peak_bid"),r.getDouble("protection_floor"),r.getBoolean("protection_armed")), t.getId());
        if (states.isEmpty()) return Decision.waitForData();
        State s = states.get(0);
        if (!Objects.equals(t.getScripCode(),s.scrip) || !Objects.equals(t.getAppUserId(),s.user)
                || !Objects.equals(t.getBrokerCredentialsId(),s.broker) || !VERSION.equals(s.version) || !t.getEntryAt().equals(s.entryAt) || Double.compare(t.getActualEntryPrice(),s.entry) != 0)
            return Decision.waitForData();
        double peak = Math.max(s.peak, bid);
        boolean armed = s.armed || peak + 1e-9 >= s.entry * 1.05;
        double floor = armed ? Math.max(s.floor, floor(s.entry, peak)) : 0;
        boolean exit = armed && bid <= floor + 1e-9;
        String message = exit ? REASON : armed ? "ARMED" : "OBSERVING";
        jdbc.update("UPDATE ngas_scalp_profit_lock_state SET peak_bid=?,protection_floor=?,protection_armed=?,last_evaluated_at=?,last_message=? WHERE trade_id=?",
                peak,floor,armed,Timestamp.valueOf(at),message,t.getId());
        if (exit || armed != s.armed || floor > s.floor) {
            String event = exit ? "EXIT_REQUESTED" : !s.armed ? "ARMED" : "FLOOR_RAISED";
            jdbc.update("INSERT INTO ngas_scalp_profit_lock_event (trade_id,observed_at,event_type,bid,peak_bid,protection_floor) VALUES (?,?,?,?,?,?)",
                    t.getId(),Timestamp.valueOf(at),event,bid,peak,floor);
            log.info("NGAS_SCALP_PROFIT_LOCK_EVENT | tradeId={} | event={} | bid={} | peakBid={} | floor={} | depth=UNVERIFIED",t.getId(),event,bid,peak,floor);
        }
        return exit ? new Decision(bid) : Decision.waitForData();
    }
    /** Fresh bid only, including when recovering an exit claim after a restart. */
    public Double currentExitBid(TriggeredTradeSetupEntity t, Instant now) {
        if (!simulator(t) || t.getScripCode() == null) return null;
        var book = quotes.getSnapshot(t.getScripCode()).orElse(null);
        return usableBook(t,book,now) ? book.getBestBid() : null;
    }
    static double floor(double entry, double peak) {
        return BigDecimal.valueOf(entry).add(BigDecimal.valueOf(peak).subtract(BigDecimal.valueOf(entry)).multiply(new BigDecimal("0.5")))
                .divide(new BigDecimal("0.05"),0,RoundingMode.FLOOR).multiply(new BigDecimal("0.05")).doubleValue();
    }
    static boolean usableBook(TriggeredTradeSetupEntity t, QuoteCacheService.QuoteSnapshot b, Instant now) {
        // Feed exchange is sometimes absent; an explicit different exchange must never be accepted.
        return b != null && Objects.equals(t.getScripCode(),b.getScripCode()) && b.isCoherentBook()
                && "SHAREKHAN_WS".equals(b.getBookSource()) && ("scrip:"+t.getScripCode()).equals(b.getBookProviderIdentity())
                && (b.getBookExchange() == null || "MX".equalsIgnoreCase(b.getBookExchange()) || "MCX".equalsIgnoreCase(b.getBookExchange()))
                && positive(b.getBestBid()) && positive(b.getBestAsk()) && b.getBestBid() <= b.getBestAsk()
                && b.getLastBookAt() != null && !b.getLastBookAt().isAfter(now) && !b.getLastBookAt().isBefore(now.minusSeconds(5))
                && t.getEntryAt() != null && !b.getLastBookAt().isBefore(t.getEntryAt().atZone(ZONE).toInstant());
    }
    private static boolean positive(Double v) { return v != null && Double.isFinite(v) && v > 0; }
    private record State(Integer scrip,Long user,Long broker,String version,LocalDateTime entryAt,double entry,double peak,double floor,boolean armed) { }
}
