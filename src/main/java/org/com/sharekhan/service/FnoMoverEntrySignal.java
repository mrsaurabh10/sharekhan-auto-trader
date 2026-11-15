package org.com.sharekhan.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** Server-owned signal identity carried by the existing persisted strategy-decision field. */
public final class FnoMoverEntrySignal {
    public static final String REJECTION = "FNO_ENTRY_SIGNAL_MISSING_OR_EXPIRED";
    private static final String PREFIX = "FNO_SIGNAL_V1;";
    private FnoMoverEntrySignal() { }

    public static String decision(LocalDateTime candleAt) {
        if (!validCandle(candleAt)) throw new IllegalArgumentException("Invalid FNO signal candle");
        return PREFIX + "FNO_CANDLE_AT=" + candleAt + ";FNO_ENTRY_DEADLINE=" + candleAt.plusMinutes(10);
    }

    public static boolean valid(String metadata, LocalDateTime now) {
        if (metadata == null || now == null || !metadata.startsWith(PREFIX)) return false;
        try {
            String[] fields = metadata.substring(PREFIX.length()).split(";", -1);
            if (fields.length != 2 || !fields[0].startsWith("FNO_CANDLE_AT=")
                    || !fields[1].startsWith("FNO_ENTRY_DEADLINE=")) return false;
            LocalDateTime candle = LocalDateTime.parse(fields[0].substring("FNO_CANDLE_AT=".length()));
            LocalDateTime deadline = LocalDateTime.parse(fields[1].substring("FNO_ENTRY_DEADLINE=".length()));
            return validCandle(candle) && deadline.equals(candle.plusMinutes(10))
                    && !now.isBefore(candle.plusMinutes(5)) && now.isBefore(deadline);
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    private static boolean validCandle(LocalDateTime candle) {
        return candle != null && candle.getMinute() % 5 == 0 && candle.getSecond() == 0 && candle.getNano() == 0
                && !candle.toLocalTime().isBefore(LocalTime.of(9, 25))
                && candle.toLocalTime().isBefore(LocalTime.of(15, 25));
    }
}
