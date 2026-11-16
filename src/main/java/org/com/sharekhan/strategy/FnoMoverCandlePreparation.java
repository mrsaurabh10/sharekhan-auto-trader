package org.com.sharekhan.strategy;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Market data only. Account workers never wait for provider I/O or place orders here. */
@Slf4j
@Component
public class FnoMoverCandlePreparation {
    private static final int REQUIRED = 76; // ATR75 includes the preceding close.
    private final StrategySupport support;
    private final Clock clock;
    private final ExecutorService executor;
    private final Runnable startPermit;
    private final Map<String, Task> tasks = new HashMap<>();
    private record Task(LocalDateTime signalAt, Instant startedAt, CompletableFuture<CandleLoad> result) { }

    @Autowired
    public FnoMoverCandlePreparation(StrategySupport support) {
        this(support, Clock.system(StrategySupport.MARKET_ZONE), workers(), new StartGate()::await);
    }

    FnoMoverCandlePreparation(StrategySupport support, Clock clock, ExecutorService executor) {
        this(support, clock, executor, () -> { });
    }

    private FnoMoverCandlePreparation(StrategySupport support, Clock clock, ExecutorService executor, Runnable startPermit) {
        this.support = support; this.clock = clock; this.executor = executor;
        this.startPermit = startPermit;
    }

    /** Pace new FNO chart reads to the documented one data request/second, without account waits. */
    private static final class StartGate {
        private long previous;
        synchronized void await() {
            long wait = previous == 0 ? 0 : 1_050_000_000L - (System.nanoTime() - previous);
            if (wait > 0) {
                try { TimeUnit.NANOSECONDS.sleep(wait); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new CancellationException("Market preparation interrupted");
                }
            }
            previous = System.nanoTime();
        }
    }

    private static ExecutorService workers() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(5, 5, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(5), r -> {
            Thread t = new Thread(r, "fno-market-" + sequence.incrementAndGet()); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    static LocalDateTime expected(LocalDateTime now) {
        return now.withSecond(0).withNano(0).minusMinutes(now.getMinute() % 5).minusMinutes(5);
    }

    private static String key(Fno925Candidate candidate) {
        return candidate.spot().getExchange() + ":" + candidate.spot().getScripCode();
    }

    /** Bounded to the frozen shortlist; live and simulated accounts share the same work. */
    public synchronized void prepare(List<Fno925Candidate> candidates, LocalDateTime now) {
        LocalDateTime signal = expected(now);
        Instant instant = clock.instant();
        tasks.entrySet().removeIf(e -> e.getValue().signalAt().toLocalDate().isBefore(now.toLocalDate())
                && e.getValue().result().isDone());
        for (Fno925Candidate candidate : candidates) {
            String key = key(candidate);
            Task old = tasks.get(key);
            if (old != null) {
                if (!old.result().isDone()) continue; // At most one provider call/instrument in flight.
                CandleLoad cached = old.result().getNow(null);
                if (old.signalAt().equals(signal) && usable(cached, signal)) continue;
                if (old.startedAt().plusSeconds(5).isAfter(instant)) continue;
            }
            if (old == null && tasks.size() >= 10) continue;
            CompletableFuture<CandleLoad> result = new CompletableFuture<>();
            Task task = new Task(signal, instant, result);
            tasks.put(key, task);
            try {
                executor.execute(() -> load(candidate, now, task));
            } catch (RejectedExecutionException full) {
                result.complete(null); // Never run provider work on an account/scheduler thread.
                log.info("FNO_MARKET_PREPARATION | symbol={} | signalAt={} | status=DEFERRED", candidate.symbol(), signal);
            }
        }
    }

    private void load(Fno925Candidate candidate, LocalDateTime now, Task task) {
        long started = System.nanoTime();
        CandleLoad load = null;
        String status = "UNAVAILABLE";
        try {
            startPermit.run();
            // A queued job must not bootstrap stale data after a bar boundary.
            if (expected(LocalDateTime.now(clock)).equals(task.signalAt())) {
                load = support.loadCompletedIndicatorCandles(candidate.spot(), REQUIRED, now);
                if (load != null) load = new CandleLoad(List.copyOf(load.candles()), load.hasVolume(), load.reason());
                status = usable(load, task.signalAt()) ? "READY" : "WAITING_COMPLETED_HISTORY";
            } else status = "EXPIRED";
        } catch (RuntimeException failure) {
            // Provider errors may contain credentials. Record only the exception class.
            log.warn("FNO_MARKET_PREPARATION_FAILURE | symbol={} | errorType={}", candidate.symbol(), failure.getClass().getSimpleName());
        } finally {
            task.result().complete(load);
            log.info("FNO_MARKET_PREPARATION | symbol={} | signalAt={} | status={} | candles={} | durationMs={}",
                    candidate.symbol(), task.signalAt(), status, load == null ? 0 : load.candles().size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    public synchronized Optional<CandleLoad> ready(Fno925Candidate candidate, LocalDateTime now) {
        Task task = tasks.get(key(candidate));
        LocalDateTime signal = expected(now);
        if (task == null || !task.signalAt().equals(signal) || !task.result().isDone()) return Optional.empty();
        CandleLoad load = task.result().getNow(null);
        return usable(load, signal) ? Optional.of(load) : Optional.empty();
    }

    private static boolean usable(CandleLoad load, LocalDateTime signal) {
        if (load == null || load.candles().size() < REQUIRED) return false;
        return load.candles().stream().anyMatch(c -> c.date().atTime(c.time()).equals(signal));
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
