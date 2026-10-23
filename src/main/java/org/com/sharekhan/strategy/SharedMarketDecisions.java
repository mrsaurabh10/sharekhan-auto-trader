package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Only immutable market decisions belong here. Account eligibility, orders and fills never do. */
@lombok.extern.slf4j.Slf4j
@Component
public class SharedMarketDecisions {
    private final Clock clock;
    private final Map<Key, java.util.concurrent.CompletableFuture<Object>> decisions = new ConcurrentHashMap<>();
    private final ThreadLocal<LocalDateTime> cycle = new ThreadLocal<>();
    private final ThreadLocal<LocalDateTime> executeBefore = new ThreadLocal<>();
    private final ThreadLocal<Set<String>> trace = new ThreadLocal<>();
    @Autowired public SharedMarketDecisions() { this(Clock.system(StrategySupport.MARKET_ZONE)); }
    public SharedMarketDecisions(Clock clock) { this.clock = clock; }
    /** Expected freshness boundary, distinct from feed, calculation and broker failures. */
    public static final class ExpiredDecisionException extends IllegalStateException {
        public ExpiredDecisionException() {
            super("Shared strategy decision expired; waiting for a fresh evaluation");
        }
    }
    private record Key(String kind, String symbol, String parameters, LocalDateTime bucket) { }

    public void withinCycle(LocalDateTime evaluatedAt, Runnable work) {
        if (cycle.get() != null) throw new IllegalStateException("Nested shared strategy cycle");
        cycle.set(evaluatedAt);
        try { work.run(); } finally { cycle.remove(); }
    }
    public StrategyApplyResponse evaluate(Supplier<StrategyApplyResponse> work) {
        if (trace.get() != null) throw new IllegalStateException("Nested strategy evaluation");
        trace.set(new LinkedHashSet<>());
        executeBefore.set((cycle.get() == null ? LocalDateTime.now(clock) : cycle.get()).plusSeconds(30));
        try {
            var result = work.get();
            if (result != null) result.setSharedSignalIds(List.copyOf(trace.get()));
            return result;
        } finally { trace.remove(); executeBefore.remove(); }
    }
    public <T> T share(String kind, String symbol, String parameters, LocalDateTime now, Supplier<T> calculate) {
        LocalDateTime at = cycle.get() == null ? now : cycle.get();
        requireCurrent(at);
        var bucket = at.withSecond(at.getSecond() < 30 ? 0 : 30).withNano(0);
        var key = new Key(kind, symbol.trim().toUpperCase(Locale.ROOT), parameters, bucket);
        decisions.keySet().removeIf(k -> k.bucket().isBefore(LocalDateTime.now(clock).minusSeconds(60)));
        if (decisions.size() >= 2_048 && !decisions.containsKey(key))
            throw new IllegalStateException("Shared decision capacity reached; wait for next cycle");
        String id = UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
        var pending = new java.util.concurrent.CompletableFuture<Object>();
        var existing = decisions.putIfAbsent(key, pending);
        var result = existing == null ? pending : existing;
        if (existing == null) {
            try {
                var value = Objects.requireNonNull(calculate.get());
                pending.complete(value);
                log.info("SHARED_MARKET_DECISION | signalId={} | kind={} | symbol={} | evaluatedAt={}", id, kind, symbol, at);
            } catch (Throwable error) {
                pending.completeExceptionally(error); decisions.remove(key, pending);
                if (error instanceof Error fatal) throw fatal;
                throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
            }
        }
        T value;
        try {
            long remaining = 30_001 - Duration.between(at, LocalDateTime.now(clock)).toMillis();
            @SuppressWarnings("unchecked") T prepared = (T) result.get(Math.max(1, remaining), java.util.concurrent.TimeUnit.MILLISECONDS);
            value = prepared;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Shared decision interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof ExpiredDecisionException expired) throw expired;
            throw new IllegalStateException("Shared market decision unavailable", failed.getCause());
        } catch (java.util.concurrent.TimeoutException expired) {
            throw new ExpiredDecisionException();
        }
        requireCurrent(at);
        if (executeBefore.get() != null && bucket.plusSeconds(30).isBefore(executeBefore.get())) executeBefore.set(bucket.plusSeconds(30));
        recordSignal(id);
        return value;
    }
    public LocalDateTime currentTime() { return LocalDateTime.now(clock); }
    public boolean executionFresh() {
        return executeBefore.get() == null || LocalDateTime.now(clock).isBefore(executeBefore.get());
    }
    public void recordSignal(String id) { if (trace.get() != null) trace.get().add(id); }
    private void requireCurrent(LocalDateTime at) {
        var current = LocalDateTime.now(clock);
        long age = Duration.between(at, current).toMillis();
        if (age < 0 || age > 30_000 || !at.toLocalDate().equals(current.toLocalDate())
                || at.toLocalTime().toSecondOfDay() / 300 != current.toLocalTime().toSecondOfDay() / 300)
            throw new ExpiredDecisionException();
    }
}
