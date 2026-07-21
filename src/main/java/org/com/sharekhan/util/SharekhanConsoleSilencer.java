package org.com.sharekhan.util;

import com.sharekhan.SharekhanConnect;

/**
 * Creates Sharekhan SDK clients with its HTTP body logger disabled.
 *
 * The SDK reads {@link SharekhanConnect#ENABLE_LOGGING} while it constructs its
 * internal OkHttp client. Setting it before construction prevents raw URLs and
 * response payloads from being written to stdout without serialising the HTTP
 * calls behind a process-wide System.out redirect.
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
        return callable.call();
    }

    public static SharekhanConnect createClient() {
        SharekhanConnect.ENABLE_LOGGING = false;
        return new SharekhanConnect();
    }

    public static SharekhanConnect createClient(String vendorKey, String apiKey, String accessToken) {
        SharekhanConnect.ENABLE_LOGGING = false;
        return new SharekhanConnect(vendorKey, apiKey, accessToken);
    }

    public static void run(SharekhanRunnable runnable) throws Exception {
        call(() -> {
            runnable.run();
            return null;
        });
    }
}
