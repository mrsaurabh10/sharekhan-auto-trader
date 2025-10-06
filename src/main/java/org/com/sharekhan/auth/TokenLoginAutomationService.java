package org.com.sharekhan.auth;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.sharekhan.SharekhanConnect;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.microsoft.playwright.BrowserType.*;

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
        Browser browser;
        try (Playwright playwright = Playwright.create()) {
            LaunchOptions options = new LaunchOptions()
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
                            "--single-process"));

            browser = playwright.chromium().
                    launch(options);
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
            //loginButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.).setTimeout(30000));
            loginButton.click(new Locator.ClickOptions().setForce(true));
            //page.waitForURL(url -> url.contains("expected-part-of-url"), new Page.WaitForURLOptions().setTimeout(60000));
            //loginButton.click();

            //page.locator("#lg_btn").click();

            Locator totpLocator = page.locator("#totp");
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));

            //page.waitForSelector("#totp", new Page.WaitForSelectorOptions().setTimeout(30000));
            Totp totp = new Totp(totpSecret);
            String otpCode = totp.now();
            totpLocator.fill(otpCode, new Locator.FillOptions().setForce(true));
            page.locator("button[onclick=\"submitOTP('TOTP')\"]").click();

            page.waitForURL(url -> url.contains("test"), new Page.WaitForURLOptions().setTimeout(120000));
            String redirectedUrl = page.url();

            Map<String, String> tokens = extractTokensFromUrl(redirectedUrl);
            String encodedToken = URLEncoder.encode(tokens.get("request_token"), StandardCharsets.UTF_8);

            JSONObject response = sharekhanConnect.generateSession(apiKey, encodedToken, null, 12345L, secretKey, 1005L);
            String accessToken = response.getJSONObject("data").getString("token");

            return new TokenResult(accessToken, 8 * 60* 60); // Sharekhan expires in 6 hour?
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
