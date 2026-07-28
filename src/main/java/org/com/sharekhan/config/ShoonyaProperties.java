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
    /** OAuth authorization endpoint; Shoonya redirects the user back with a short-lived code. */
    private String oauthUrl = "https://api.shoonya.com/OAuthlogin/authorize/oauth";
    private String clientId;
    private String secretCode;
    private String uid;
    private String password;
    private String totpSecret;
    /** Shoonya's OAuth response does not provide a documented expiry. */
    private long accessTokenTtlSeconds = 8 * 60 * 60;
}
