package org.com.sharekhan.util;


/**
 * Utility to suppress the verbose System.out logs emitted by the Sharekhan SDK.
 * The SDK prints entire HTTP payloads to stdout for every request/response, which
 * pollutes our structured logs. We temporarily redirect System.out to a null
 * stream while invoking the SDK and restore it afterwards.
 */
public final class SharekhanConsoleSilencer {

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
        // Do not redirect the process-wide System.out around a network request. The
        // previous implementation held one global lock until the SDK returned,
        // serialising entries, exits and status polls behind a slow broker call.
        // SDK output is handled by normal application log configuration instead.
        return callable.call();
    }

    public static void run(SharekhanRunnable runnable) throws Exception {
        call(() -> {
            runnable.run();
            return null;
        });
    }
}
