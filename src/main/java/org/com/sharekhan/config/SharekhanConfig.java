package org.com.sharekhan.config;

import com.sharekhan.SharekhanConnect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharekhanConfig {

    @Bean
    public SharekhanConnect sharekhanConnect() {
        // Inject access token later — here it's null by default
        return new SharekhanConnect(null, "your-api-key", "your-access-token");
    }
}
