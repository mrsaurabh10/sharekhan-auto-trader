package org.com.sharekhan.strategy.straddle;

import java.time.*;
import java.util.*;

/** Shared live/replay schedule. All timestamps are exchange-local (IST). */
public final class ExpiryStraddleRules {
    private ExpiryStraddleRules() { }
    public static final String TEMPLATE_ID = "EXPIRY_LONG_STRADDLE";
    public record Session(String id, LocalTime entry, LocalTime exit) {
        public boolean entryWindow(LocalTime now) {
            return !now.isBefore(entry) && now.isBefore(entry.plusMinutes(1));
        }
    }
    public static final List<Session> SESSIONS = List.of(
            new Session("AM", LocalTime.of(9,30), LocalTime.of(10,30)),
            new Session("PM", LocalTime.of(13,30), LocalTime.of(14,30)));
    public static boolean entryAllowed(String source, LocalDateTime now) {
        if(source==null || !source.startsWith("expiry-straddle:"+now.toLocalDate()+":")) return false;
        return SESSIONS.stream().anyMatch(s->source.endsWith(":"+s.id()) && s.entryWindow(now.toLocalTime()));
    }
    public static String symbol(String input) {
        String value = input == null || input.isBlank() ? "NIFTY" : input.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9]{0,15}")) throw new IllegalArgumentException("Enter one underlying symbol");
        return value;
    }
    public static int lots(Integer value) {
        int lots = value == null ? 1 : value;
        if (lots < 1 || lots > 1000) throw new IllegalArgumentException("Choose between 1 and 1000 lots");
        return lots;
    }
    /** Lower strike wins an exact tie; only strikes listed for BOTH legs may be supplied. */
    public static double atm(Collection<Double> commonStrikes, double spot) {
        if (!Double.isFinite(spot) || spot <= 0) throw new IllegalArgumentException("Fresh spot price required");
        return commonStrikes.stream().filter(Objects::nonNull).filter(s -> Double.isFinite(s) && s > 0)
                .min(Comparator.<Double>comparingDouble(s -> Math.abs(s - spot)).thenComparingDouble(s -> s))
                .orElseThrow(() -> new IllegalArgumentException("No common CE/PE strike for today's expiry"));
    }
}
