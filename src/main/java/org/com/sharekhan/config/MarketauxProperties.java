package org.com.sharekhan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.marketaux")
public class MarketauxProperties {
    private boolean enabled = true;
    private String apiToken;
    private String baseUrl = "https://api.marketaux.com/v1";
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 10_000;
    private boolean indiaSentimentCollectionEnabled = true;
    private int indiaSentimentCallsPerDay = 100;
    private int indiaSentimentArticlesPerCall = 50;
}
