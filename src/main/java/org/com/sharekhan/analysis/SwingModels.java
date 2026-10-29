package org.com.sharekhan.analysis;

import java.time.*;
import java.util.*;

public final class SwingModels {
    private SwingModels() { }
    public record Plan(String symbol, int scripCode, String exchange, String setup, String provider,
                       LocalDate dataDate, LocalDate validSession, double entry, double maxEntry, double stop,
                       double target1, double target2, double riskPerShare, double riskPercent, double atr14,
                       double rsi14, double turnover20, String rationale, List<String> cancelIf) { }
    public record StockResult(String symbol, int scripCode, String status, String detail, LocalDate lastDate,
                              boolean above50, boolean above200, Plan plan) { }
    public record Report(String version, LocalDate dataDate, LocalDate validSession, Instant generatedAt,
                         String status, int universeCount, int coveredCount, double breadthAbove50,
                         List<Plan> plans, List<StockResult> stocks, List<String> notes) { }
    public record Observation(String symbol, LocalDate planDate, LocalDate observedThrough, String status, String detail) { }
}
