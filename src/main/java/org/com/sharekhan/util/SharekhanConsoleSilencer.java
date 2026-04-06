package org.com.sharekhan.util;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Utility to suppress the verbose System.out logs emitted by the Sharekhan SDK.
 * The SDK prints entire HTTP payloads to stdout for every request/response, which
 * pollutes our structured logs. We temporarily redirect System.out to a null
 * stream while invoking the SDK and restore it afterwards.
 */
public final class SharekhanConsoleSilencer {

    private static final ReentrantLock lock = new ReentrantLock();

    private SharekhanConsoleSilencer() {
    }

    @FunctionalInterface
    public interface SharekhanCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    public interface SharekhanRunnable {
        void run() throws Exception;
    }

    public static <T> T call(SharekhanCallable<T> callable) throws Exception {
        lock.lock();
        PrintStream originalOut = System.out;
        PrintStream silentOut = new PrintStream(OutputStream.nullOutputStream());
        try {
            System.setOut(silentOut);
            return callable.call();
        } finally {
            System.setOut(originalOut);
            silentOut.close();
            lock.unlock();
        }
    }

    public static void run(SharekhanRunnable runnable) throws Exception {
        call(() -> {
            runnable.run();
            return null;
        });
    }
}
