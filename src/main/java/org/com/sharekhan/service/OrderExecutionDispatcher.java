package org.com.sharekhan.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Keeps broker HTTP work out of market-data and scheduler threads.
 * A key may be queued or running only once, which makes re-delivery of an LTP
 * tick harmless while an entry or exit is already in flight.
 */
@Component
@Slf4j
public class OrderExecutionDispatcher {

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor entryExecutor;
    private final ThreadPoolExecutor exitExecutor;
    private final ThreadPoolExecutor simulatorExecutor;

    @Autowired
    public OrderExecutionDispatcher(@Value("${app.trading.order-execution.entry-pool-size:4}") int entryPoolSize,
                                    @Value("${app.trading.order-execution.exit-pool-size:4}") int exitPoolSize,
                                    @Value("${app.trading.order-execution.entry-queue-capacity:200}") int entryQueueCapacity,
                                    @Value("${app.trading.order-execution.exit-queue-capacity:100}") int exitQueueCapacity,
                                    @Value("${app.trading.order-execution.simulator-pool-size:1}") int simulatorPoolSize,
                                    @Value("${app.trading.order-execution.simulator-queue-capacity:50}") int simulatorQueueCapacity) {
        entryExecutor = newExecutor("entry-order-execution", entryPoolSize, entryQueueCapacity);
        exitExecutor = newExecutor("exit-order-execution", exitPoolSize, exitQueueCapacity);
        simulatorExecutor = newExecutor("simulator-order-execution", simulatorPoolSize, simulatorQueueCapacity);
    }

    private ThreadPoolExecutor newExecutor(String threadName, int poolSize, int queueCapacity) {
        return new ThreadPoolExecutor(
                Math.max(1, poolSize), Math.max(1, poolSize),
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(String key, Runnable task) {
        if (key == null || task == null || !inFlight.add(key)) {
            return false;
        }
        try {
            executorFor(key).execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("Order execution task {} failed", key, e);
                } finally {
                    inFlight.remove(key);
                }
            });
            return true;
        } catch (RuntimeException e) {
            inFlight.remove(key);
            log.warn("Order execution queue rejected {}: {}", key, e.getMessage());
            return false;
        }
    }

    public boolean isInFlight(String key) {
        return key != null && inFlight.contains(key);
    }

    private ThreadPoolExecutor executorFor(String key) {
        if (key.startsWith("SIM:")) {
            return simulatorExecutor;
        }
        return key.startsWith("EXIT:") ? exitExecutor : entryExecutor;
    }

    @PreDestroy
    void shutdown() {
        entryExecutor.shutdown();
        exitExecutor.shutdown();
        simulatorExecutor.shutdown();
    }
}
