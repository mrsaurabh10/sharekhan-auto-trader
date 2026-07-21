package org.com.sharekhan.config;

import com.sharekhan.SharekhanConnect;
import org.com.sharekhan.util.SharekhanConsoleSilencer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharekhanConfig {

    @Bean
    public SharekhanConnect sharekhanConnect() {
        // Keep the SDK's OkHttp BODY logger off. Broker request/response payloads
        // are noisy and may contain sensitive data; do not suppress them by
        // serialising live requests through a global stdout lock.
        SharekhanConnect.ENABLE_LOGGING = false;
        // Inject access token later — here it's null by default
        return SharekhanConsoleSilencer.createClient(null, "your-api-key", "your-access-token");
    }
}
