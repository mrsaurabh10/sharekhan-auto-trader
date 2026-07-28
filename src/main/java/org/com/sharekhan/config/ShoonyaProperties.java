package org.com.sharekhan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.shoonya")
public class ShoonyaProperties {
    /** The Noren REST base. GetQuotes is invoked at {api-url}/GetQuotes. */
    private String apiUrl = "https://api.shoonya.com/NorenWClientAPI";
    /** Shoonya documents QuickAuth on the NorenWClientTP host, separately from GetQuotes. */
    private String authUrl = "https://api.shoonya.com/NorenWClientTP";
    private String userId;
    private String password;
    private String vendorCode;
    private String apiKey;
    private String deviceId;
    private String totpSecret;
}
