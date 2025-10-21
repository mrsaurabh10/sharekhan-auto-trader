package org.com.sharekhan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.mstock")
public class MStockProperties {
    /**
     * MStock API key. Prefer setting via environment variable APP_MSTOCK_API_KEY or external config.
     */
    private String apiKey;

    /**
     * TOTP secret used to generate one-time codes for MStock. Prefer setting via environment variable APP_MSTOCK_TOTP_SECRET.
     */
    private String totpSecret;
}

