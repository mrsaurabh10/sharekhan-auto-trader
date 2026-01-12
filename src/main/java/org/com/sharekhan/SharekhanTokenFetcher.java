package org.com.sharekhan;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.sharekhan.SharekhanConnect;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class SharekhanTokenFetcher {

    public String fetchAccessToken(String clientCode, String password, String totpSecret, String apiKey, String secretKey) {
        SharekhanConnect sharekhanConnect = new SharekhanConnect();
        // Note: clientCode is not used in getLoginURL but might be needed if the flow changes.
        // The original TokenLoginAutomationService used apiKey, null, "1234", 1234L
        String loginUrl = sharekhanConnect.getLoginURL(apiKey, null, "1234", 1234L);
        
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setArgs(Arrays.asList("--disable-gpu",
                             "--no-sandbox",
                             "--disable-dev-shm-usage",
                             "--disable-software-rasterizer",
                             "--disable-extensions",
                             "--disable-background-networking",
                             "--disable-default-apps",
                             "--disable-popup-blocking",
                             "--mute-audio",
                             "--single-process")))) {
            Page page = browser.newPage();
            page.navigate(loginUrl, new Page.NavigateOptions().setTimeout(1200000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Step 2: Wait explicitly for the password field (or your critical UI element) to appear
            page.waitForSelector("#mpwd", new Page.WaitForSelectorOptions().setTimeout(200000));

            Locator passwordLocator = page.locator("#mpwd");
            passwordLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            passwordLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));

            page.locator("#mpwd").fill(password, new Locator.FillOptions().setForce(true));

            Locator loginButton = page.locator("#lg_btn");
            loginButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));
            loginButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            loginButton.click(new Locator.ClickOptions().setForce(true));

            Locator totpLocator = page.locator("#totp");
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));

            Totp totp = new Totp(totpSecret);
            String otpCode = totp.now();
            totpLocator.fill(otpCode, new Locator.FillOptions().setForce(true));
            page.locator("button[onclick=\"submitOTP('TOTP')\"]").click();

            // Wait for redirection to a URL containing "request_token" (or similar indicator of success)
            // The original code waited for "test" but also extracted tokens. Let's be robust.
            // Actually, the original code waited for url -> url.contains("test")
            // But then extracted request_token. Let's assume the redirect URL contains the token.
            page.waitForURL(url -> url.contains("request_token") || url.contains("test"), new Page.WaitForURLOptions().setTimeout(120000));
            String redirectedUrl = page.url();

            Map<String, String> tokens = extractTokensFromUrl(redirectedUrl);
            String requestToken = tokens.get("request_token");
            
            if (requestToken == null) {
                 throw new RuntimeException("Could not extract request_token from URL: " + redirectedUrl);
            }

            String encodedToken = URLEncoder.encode(requestToken, StandardCharsets.UTF_8);

            // Exchange request_token for access_token
            // The original code used: sharekhanConnect.generateSession(apiKeyToUse, encodedToken, null, 12345L, secretToUse, 1005L);
            JSONObject response = sharekhanConnect.generateSession(apiKey, encodedToken, null, 12345L, secretKey, 1005L);
            
            if (response.has("data")) {
                return response.getJSONObject("data").getString("token");
            } else {
                throw new RuntimeException("Failed to generate session, response: " + response);
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Login automation failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> extractTokensFromUrl(String url) {
        Map<String, String> result = new HashMap<>();
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                        String value = kv[1];
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token from URL: " + e.getMessage(), e);
        }
        return result;
    }
}
