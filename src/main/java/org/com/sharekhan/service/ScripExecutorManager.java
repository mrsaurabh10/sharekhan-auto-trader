package org.com.sharekhan.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ScripExecutorManager {
    private final ConcurrentHashMap<Integer, ExecutorService> triggerExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ExecutorService> monitorExecutors = new ConcurrentHashMap<>();

    public ExecutorService getTriggerExecutor(int scripCode) {
        return triggerExecutors.computeIfAbsent(scripCode,
            code -> Executors.newSingleThreadExecutor(r -> new Thread(r, "trigger-scrip-" + code)));
    }

    public ExecutorService getMonitorExecutor(int scripCode) {
        return monitorExecutors.computeIfAbsent(scripCode,
            code -> Executors.newSingleThreadExecutor(r -> new Thread(r, "monitor-scrip-" + code)));
    }

    public void submitTriggerTask(int scripCode, Runnable task) {
        getTriggerExecutor(scripCode).submit(task);
    }

    public void submitMonitorTask(int scripCode, Runnable task) {
        getMonitorExecutor(scripCode).submit(task);
    }

    public void shutdownTrigger(int scripCode) {
        ExecutorService exec = triggerExecutors.remove(scripCode);
        if (exec != null) exec.shutdownNow();
    }

    public void shutdownMonitor(int scripCode) {
        ExecutorService exec = monitorExecutors.remove(scripCode);
        if (exec != null) exec.shutdownNow();
    }
}
