package org.com.sharekhan.strategy.mcx;

import java.time.*;

/** Scope is deliberately limited to this strategy; existing equity sessions are unchanged. */
public final class NaturalGasSession {
    public static final String TEMPLATE = "MCX_NATURALGAS_ATM_BREAKOUT";
    public static final String SOURCE = "strategy:" + TEMPLATE;
    public static final LocalTime OPEN = LocalTime.of(9, 0);
    public static final LocalTime ENTRY_CUTOFF = LocalTime.of(23, 0);
    public static final LocalTime SQUARE_OFF = LocalTime.of(23, 20);
    public static final LocalTime CLOSE = LocalTime.of(23, 30);
    private NaturalGasSession() { }
    public static boolean isTemplate(String id) { return TEMPLATE.equalsIgnoreCase(id) || NaturalGasScalpingStrategy.TEMPLATE.equalsIgnoreCase(id); }
    public static boolean owns(String source) { return SOURCE.equalsIgnoreCase(source) || NaturalGasScalpingStrategy.SOURCE.equalsIgnoreCase(source); }
    public static boolean entryAllowed(LocalDateTime now) {
        return weekday(now) && !now.toLocalTime().isBefore(OPEN) && now.toLocalTime().isBefore(ENTRY_CUTOFF);
    }
    public static boolean marketOpen(LocalDateTime now) {
        return weekday(now) && !now.toLocalTime().isBefore(OPEN) && now.toLocalTime().isBefore(CLOSE);
    }
    private static boolean weekday(LocalDateTime now) {
        return now != null && now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
