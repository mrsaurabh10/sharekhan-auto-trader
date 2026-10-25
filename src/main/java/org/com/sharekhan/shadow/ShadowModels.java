package org.com.sharekhan.shadow;

import java.time.*;
import java.util.*;

/** Immutable observations only. No broker sessions or orders belong in these records. */
public final class ShadowModels {
    private ShadowModels() { }
    public record Book(Double bid, Double ask, Double ltp, Instant bookAt, Instant ltpAt,
                       String source, String exchange, String providerIdentity,
                       Double bidQuantity, Double askQuantity, boolean coherent) { }
    public record Sample(Instant at, Book book, Double spot, Instant spotAt,
                         LocalDateTime minuteAt, Double minuteClose) { }
    public record Decision(String id, LocalDate day, Instant at, Long userId, Long brokerId, Long requestId,
                           String setupKey, String contractKey, String symbol, String exchange, Integer code,
                           Integer spotCode, String expiry, Double strike, String side, int lots, long quantity,
                           double entry, double stop, List<Double> targets, double softSpread, double hardSpread,
                           double maxAgeMs, double priceCeiling, boolean signalFresh, boolean entryWindowOpen,
                           Sample sample, LocalDateTime signalCandleAt, String rulesVersion, List<String> sharedSignalIds) { }
    public record Watch(String key, LocalDate day, String exchange, int code, int spotCode) { }
    public record Event(String id, LocalDate day, String key, String type, Long userId, Instant at, String payload) { }
    public static boolean price(Double v) { return v != null && Double.isFinite(v) && v > 0; }
}
