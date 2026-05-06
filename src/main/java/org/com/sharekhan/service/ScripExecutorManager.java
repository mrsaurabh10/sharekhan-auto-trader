package org.com.sharekhan.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class ScripExecutorManager {
    private final ConcurrentHashMap<Integer, ExecutorService> triggerExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> triggerRefCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ExecutorService> monitorExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> monitorRefCounts = new ConcurrentHashMap<>();

    public ExecutorService getTriggerExecutor(int scripCode) {
        return triggerExecutors.computeIfAbsent(scripCode,
            code -> newLatestOnlyExecutor("trigger-scrip-" + code));
    }

    public ExecutorService getMonitorExecutor(int scripCode) {
        return monitorExecutors.computeIfAbsent(scripCode,
            code -> newLatestOnlyExecutor("monitor-scrip-" + code));
    }

    private ExecutorService newLatestOnlyExecutor(String threadName) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public void submitTriggerTask(int scripCode, Runnable task) {
        getTriggerExecutor(scripCode).submit(task);
    }

    public void submitMonitorTask(int scripCode, Runnable task) {
        getMonitorExecutor(scripCode).submit(task);
    }

    public void registerTriggerUsage(int scripCode) {
        triggerRefCounts.merge(scripCode, 1, Integer::sum);
    }

    public void registerMonitorUsage(int scripCode) {
        monitorRefCounts.merge(scripCode, 1, Integer::sum);
    }

    public void releaseTrigger(int scripCode) {
        triggerRefCounts.computeIfPresent(scripCode, (code, count) -> {
            int next = count - 1;
            if (next <= 0) {
                shutdownTrigger(code);
                return null;
            }
            return next;
        });
    }

    public void releaseMonitor(int scripCode) {
        monitorRefCounts.computeIfPresent(scripCode, (code, count) -> {
            int next = count - 1;
            if (next <= 0) {
                shutdownMonitor(code);
                return null;
            }
            return next;
        });
    }

    private void shutdownTrigger(int scripCode) {
        ExecutorService exec = triggerExecutors.remove(scripCode);
        if (exec != null) exec.shutdownNow();
    }

    private void shutdownMonitor(int scripCode) {
        ExecutorService exec = monitorExecutors.remove(scripCode);
        if (exec != null) exec.shutdownNow();
    }

}
