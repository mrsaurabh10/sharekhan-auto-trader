package org.com.sharekhan.util;

import com.sharekhan.SharekhanConnect;
import java.io.PrintStream;

/**
 * Creates Sharekhan SDK clients with its HTTP body logger disabled.
 *
 * The SDK reads {@link SharekhanConnect#ENABLE_LOGGING} while it constructs its
 * internal OkHttp client. Setting it before construction prevents raw URLs and
 * response payloads from being written to stdout without serialising the HTTP
 * calls behind a process-wide System.out redirect.
 */
public final class SharekhanConsoleSilencer {

    private static final Object STDOUT_FILTER_LOCK = new Object();

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
        installSdkConsoleFilter();
        return callable.call();
    }

    public static SharekhanConnect createClient() {
        installSdkConsoleFilter();
        SharekhanConnect.ENABLE_LOGGING = false;
        return new SharekhanConnect();
    }

    public static SharekhanConnect createClient(String vendorKey, String apiKey, String accessToken) {
        installSdkConsoleFilter();
        SharekhanConnect.ENABLE_LOGGING = false;
        return new SharekhanConnect(vendorKey, apiKey, accessToken);
    }

    private static void installSdkConsoleFilter() {
        if (System.out instanceof SharekhanSdkFilteringPrintStream) {
            return;
        }
        synchronized (STDOUT_FILTER_LOCK) {
            if (!(System.out instanceof SharekhanSdkFilteringPrintStream)) {
                System.setOut(new SharekhanSdkFilteringPrintStream(System.out));
            }
        }
    }

    /**
     * SharekhanConnect writes normal API URLs and pretty-printed JSON directly
     * to System.out. Filter only those payloads while leaving normal application
     * stdout untouched. Unlike temporary redirection, this adds no contention to
     * the broker HTTP call itself.
     */
    private static final class SharekhanSdkFilteringPrintStream extends PrintStream {
        private final PrintStream delegate;

        private SharekhanSdkFilteringPrintStream(PrintStream delegate) {
            super(delegate, true);
            this.delegate = delegate;
        }

        @Override
        public void println(String value) {
            if (!isSharekhanSdkPayload(value)) {
                delegate.println(value);
            }
        }

        private boolean isSharekhanSdkPayload(String value) {
            if (value == null) {
                return false;
            }
            String trimmed = value.trim();
            if (trimmed.startsWith("https://api.sharekhan.com/skapi/")) {
                return true;
            }
            return trimmed.startsWith("{")
                    && trimmed.contains("\"data\"")
                    && (trimmed.contains("\"timestamp\"") || trimmed.contains("\"message\""));
        }
    }

    public static void run(SharekhanRunnable runnable) throws Exception {
        call(() -> {
            runnable.run();
            return null;
        });
    }
}
