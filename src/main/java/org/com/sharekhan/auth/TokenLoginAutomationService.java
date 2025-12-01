package org.com.sharekhan.auth;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.sharekhan.SharekhanConnect;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.repository.BrokerCredentialsRepository;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.microsoft.playwright.BrowserType.*;

@Service
@RequiredArgsConstructor
public class TokenLoginAutomationService implements BrokerAuthProvider {

    public static final String clientCode = "SGUPTA78";
    public static final Long customerId = 73196L;
    private static final String password = "Anvi@2023";
    private static final String totpSecret = "3VNUAS5GNB5UXCXR";
    private static final String secretKey = "iOpn2GrHHzmjdWu795RRw79d0OPZn7jh";
    public static final String apiKey = "M57X7RqA9C43IOq8iJSySWv8LAD2DzkM";

    private final CryptoService cryptoService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @Override
    public AuthTokenResult loginAndFetchToken() {
        // Try to load credentials for the configured default customer if present
        try {
            var opt = brokerCredentialsRepository.findTopByBrokerNameAndAppUserIdAndActiveTrue("SHAREKHAN", customerId);
            if (opt.isEmpty()) {
                opt = brokerCredentialsRepository.findTopByBrokerNameAndAppUserId("SHAREKHAN", customerId);
            }
            if (opt.isPresent()) {
                return loginAndFetchToken(opt.get());
            }
        } catch (Exception e) {
            // ignore db errors and fallback to defaults
        }
        // fallback to existing behavior
        return loginAndFetchToken(null);
    }

    @Override
    public AuthTokenResult loginAndFetchToken(org.com.sharekhan.entity.BrokerCredentialsEntity creds) {
        // If creds provided, prefer values from creds; otherwise fall back to static defaults
        final String apiKeyToUse = (creds != null && creds.getApiKey() != null && !creds.getApiKey().isBlank()) ? maybeDecrypt(creds.getApiKey()) : apiKey;
        final String pwdToUse = (creds != null && creds.getBrokerPassword() != null && !creds.getBrokerPassword().isBlank()) ? maybeDecrypt(creds.getBrokerPassword()) : password;
        final String totpToUse = (creds != null && creds.getTotpSecret() != null && !creds.getTotpSecret().isBlank()) ? maybeDecrypt(creds.getTotpSecret()) : totpSecret;
        final String secretToUse = (creds != null && creds.getSecretKey() != null && !creds.getSecretKey().isBlank()) ? maybeDecrypt(creds.getSecretKey()) : secretKey;

        SharekhanConnect sharekhanConnect = new SharekhanConnect();
        String loginUrl = sharekhanConnect.getLoginURL(apiKeyToUse, null, "1234", 1234L);
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new LaunchOptions()
                     .setHeadless(false)
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

            page.locator("#mpwd").fill(pwdToUse, new Locator.FillOptions().setForce(true));

            Locator loginButton = page.locator("#lg_btn");
            loginButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));
            loginButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            loginButton.click(new Locator.ClickOptions().setForce(true));

            Locator totpLocator = page.locator("#totp");
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            totpLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));

            Totp totp = new Totp(totpToUse);
            String otpCode = totp.now();
            totpLocator.fill(otpCode, new Locator.FillOptions().setForce(true));
            page.locator("button[onclick=\"submitOTP('TOTP')\"]").click();

            page.waitForURL(url -> url.contains("test"), new Page.WaitForURLOptions().setTimeout(120000));
            String redirectedUrl = page.url();

            Map<String, String> tokens = extractTokensFromUrl(redirectedUrl);
            String encodedToken = URLEncoder.encode(tokens.get("request_token"), StandardCharsets.UTF_8);

            JSONObject response = sharekhanConnect.generateSession(apiKeyToUse, encodedToken, null, 12345L, secretToUse, 1005L);
            String accessToken = response.getJSONObject("data").getString("token");

            return new AuthTokenResult(accessToken, 8 * 60 * 60);
        } catch (Exception e) {
            throw new RuntimeException("Login automation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public org.com.sharekhan.enums.Broker getBroker() {
        return org.com.sharekhan.enums.Broker.SHAREKHAN;
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

    // Attempt to decrypt, but fall back to raw value on any error so existing plaintext values still work
    private String maybeDecrypt(String value) {
        if (value == null) return null;
        try {
            return cryptoService.decrypt(value);
        } catch (Exception e) {
            return value;
        }
    }
}
