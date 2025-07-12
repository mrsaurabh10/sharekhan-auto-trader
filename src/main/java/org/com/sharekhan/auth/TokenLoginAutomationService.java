package org.com.sharekhan.auth;

import com.microsoft.playwright.*;
import com.sharekhan.SharekhanConnect;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class TokenLoginAutomationService {

    public static final String clientCode = "SGUPTA78";
    public static final Long customerId = 73196L;
    private static final String password = "Anvi@2023";
    private static final String totpSecret = "3VNUAS5GNB5UXCXR";
    private static final String secretKey = "iOpn2GrHHzmjdWu795RRw79d0OPZn7jh";
    public static final String apiKey = "M57X7RqA9C43IOq8iJSySWv8LAD2DzkM";

    public record TokenResult(String token, long expiresIn) {}

    public TokenResult loginAndFetchToken() {
        SharekhanConnect sharekhanConnect = new SharekhanConnect();
        String loginUrl = sharekhanConnect.getLoginURL(apiKey, null, "1234", 1234L);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(loginUrl);

            page.locator("#mpwd").fill(password);
            page.locator("#lg_btn").click();

            page.waitForSelector("#totp", new Page.WaitForSelectorOptions().setTimeout(8000));
            Totp totp = new Totp(totpSecret);
            String otpCode = totp.now();
            page.locator("#totp").fill(otpCode);
            page.locator("button[onclick=\"submitOTP('TOTP')\"]").click();

            page.waitForURL(url -> url.contains("test"), new Page.WaitForURLOptions().setTimeout(15000));
            String redirectedUrl = page.url();

            Map<String, String> tokens = extractTokensFromUrl(redirectedUrl);
            String encodedToken = URLEncoder.encode(tokens.get("request_token"), StandardCharsets.UTF_8);

            JSONObject response = sharekhanConnect.generateSession(apiKey, encodedToken, null, 12345L, secretKey, 1005L);
            String accessToken = response.getJSONObject("data").getString("token");

            return new TokenResult(accessToken, 36000*6); // Sharekhan expires in 1 hour?
        } catch (Exception e) {
            throw new RuntimeException("Login automation failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> extractTokensFromUrl(String url) {
        Map<String, String> result = new HashMap<>();
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String value = kv[1];
                    result.put(key, value);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token from URL: " + e.getMessage(), e);
        }
        return result;
    }
}
