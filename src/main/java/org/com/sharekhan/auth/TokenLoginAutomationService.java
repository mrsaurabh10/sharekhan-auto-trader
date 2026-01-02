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
    // All previously hardcoded credentials have been removed. Credentials must
    // come from BrokerCredentialsEntity records.

    private final CryptoService cryptoService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @Override
    public AuthTokenResult loginAndFetchToken() {
        // Resolve any active Sharekhan broker credentials; require at least one configured
        try {
            var all = brokerCredentialsRepository.findAll();
            org.com.sharekhan.entity.BrokerCredentialsEntity chosen = null;
            for (var b : all) {
                if (b == null) continue;
                if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")
                        && Boolean.TRUE.equals(b.getActive())) { chosen = b; break; }
            }
            if (chosen == null) {
                for (var b : all) {
                    if (b == null) continue;
                    if (b.getBrokerName() != null && b.getBrokerName().equalsIgnoreCase("Sharekhan")) { chosen = b; break; }
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("No Sharekhan broker configured. Please add broker credentials.");
            }
            return loginAndFetchToken(chosen);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve broker credentials for login: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthTokenResult loginAndFetchToken(org.com.sharekhan.entity.BrokerCredentialsEntity creds) {
        if (creds == null) {
            throw new IllegalStateException("Broker credentials are required for login (no hardcoded defaults).");
        }
        final String apiKeyToUse = requireNonBlank(maybeDecrypt(creds.getApiKey()), "apiKey");
        final String pwdToUse = requireNonBlank(maybeDecrypt(creds.getBrokerPassword()), "brokerPassword");
        final String totpToUse = requireNonBlank(maybeDecrypt(creds.getTotpSecret()), "totpSecret");
        final String secretToUse = requireNonBlank(maybeDecrypt(creds.getSecretKey()), "secretKey");

        SharekhanConnect sharekhanConnect = new SharekhanConnect();
        String loginUrl = sharekhanConnect.getLoginURL(apiKeyToUse, null, "1234", 1234L);
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new LaunchOptions()
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

    private String requireNonBlank(String v, String fieldName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required broker credential: " + fieldName);
        }
        return v;
    }
}
