package org.com.sharekhan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides lightweight, in-memory critical sections to serialize entry/exit order placements per trade identity.
 * Prevents duplicate Sharekhan API submissions when multiple threads attempt the same action concurrently.
 */
@Slf4j
@Component
public class OrderPlacementGuard {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String key, Duration wait, Callable<T> action) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Lock key must not be blank");
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = false;
        try {
            long timeoutMs = wait != null ? wait.toMillis() : 0L;
            if (timeoutMs <= 0) {
                acquired = lock.tryLock();
            } else {
                acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            }

            if (!acquired) {
                throw new LockAcquisitionException("Timeout while waiting for order lock '" + key + "'");
            }

            return action.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted while waiting for order lock '" + key + "'", e);
        } catch (LockAcquisitionException e) {
            throw e;
        } catch (Exception e) {
            throw unwrapRuntime(e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (!lock.isLocked()) {
                locks.remove(key, lock);
            }
        }
    }

    public void runWithLock(String key, Duration wait, Runnable runnable) {
        withLock(key, wait, () -> {
            runnable.run();
            return null;
        });
    }

    private RuntimeException unwrapRuntime(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(exception);
    }

    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }

        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
