package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import java.time.LocalDateTime;

/** Pure decision rules shared by the persistent policy and offline verification. */
public final class AdxDmiIndexExitRules {
    private AdxDmiIndexExitRules() { }
    public static final String VERSION="INDEX_15M_V1";
    public static final String STOP="ADX_INDEX_STOP", TARGET="ADX_INDEX_TARGET", TIMEOUT="ADX_NO_T1_15M";
    public static boolean owns(TriggeredTradeSetupEntity trade) {
        return trade!=null && AdxDmiRules.premium(trade.getSource())
                && VERSION.equals(AdxDmiEntryAdmission.field(trade.getComment(),"ADX_EXIT"));
    }
    public record State(LocalDateTime startedAt,LocalDateTime t1ReachedAt,LocalDateTime timedOutAt) { }
    public static State observe(State state,boolean pe,double t1,Double spot,LocalDateTime now) {
        if (now.isBefore(state.startedAt()) || state.t1ReachedAt()!=null || state.timedOutAt()!=null) return state;
        // Deadline wins at exactly 15 minutes, matching the causal research replay.
        if (!now.isBefore(state.startedAt().plusMinutes(15))) return new State(state.startedAt(),null,now);
        if (usable(spot) && reached(spot,t1,pe)) return new State(state.startedAt(),now,null);
        return state;
    }
    public static String exit(State state,boolean pe,double stop,double target,Double spot,LocalDateTime now) {
        if (now.isBefore(state.startedAt())) return null;
        if (!now.toLocalDate().equals(state.startedAt().toLocalDate()) || !now.toLocalTime().isBefore(AdxDmiRules.SQUARE_OFF)) return "INTRADAY_CLOSE";
        if (state.timedOutAt()!=null) return TIMEOUT;
        if (!usable(spot)) return null;
        if (reached(spot,stop,!pe)) return STOP;
        return reached(spot,target,pe)?TARGET:null;
    }
    private static boolean reached(double spot,double level,boolean below) {return below?spot<=level+1e-8:spot>=level-1e-8;}
    private static boolean usable(Double spot) {return spot!=null && Double.isFinite(spot) && spot>0;}
}
