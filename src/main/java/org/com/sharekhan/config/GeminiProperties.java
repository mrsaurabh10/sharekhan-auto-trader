package org.com.sharekhan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.gemini")
public class GeminiProperties {
    private boolean enabled = true;
    private String apiKey;
    private String model = "gemini-2.5-flash";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private int maxOutputTokens = 2048;
    private Integer thinkingBudget = 0;
}
