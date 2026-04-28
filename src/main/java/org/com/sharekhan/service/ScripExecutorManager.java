package org.com.sharekhan.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ScripExecutorManager {
    private final ConcurrentHashMap<Integer, ExecutorService> triggerExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> triggerRefCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ExecutorService> monitorExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> monitorRefCounts = new ConcurrentHashMap<>();

    public ExecutorService getTriggerExecutor(int scripCode) {
        return triggerExecutors.computeIfAbsent(scripCode,
            code -> Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "trigger-scrip-" + code);
                t.setDaemon(true);
                return t;
            }));
    }

    public ExecutorService getMonitorExecutor(int scripCode) {
        return monitorExecutors.computeIfAbsent(scripCode,
            code -> Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "monitor-scrip-" + code);
                t.setDaemon(true);
                return t;
            }));
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
