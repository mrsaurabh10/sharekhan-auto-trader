package org.com.sharekhan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.sharekhan")
public class SharekhanProperties {
    private String clientCode;
    private String password;
    private String totpSecret;
}

