package org.com.sharekhan;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import org.com.sharekhan.util.TOTPGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class SharekhanTokenFetcher {

    private static final String LOGIN_URL = "https://www.sharekhan.com/login";
    private static final String REDIRECT_URL_CONTAINS = "accessToken";

    public String fetchAccessToken(String clientCode, String password, String totpSecret) {
        AtomicReference<String> accessToken = new AtomicReference<>(null);

        try (Playwright playwright = Playwright.create()) {

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)  // Change to true for headless
                    .setArgs(Arrays.asList(
                            "--disable-blink-features=AutomationControlled",
                            "--disable-popup-blocking",
                            "--disable-extensions",
                            "--no-sandbox",
                            "--disable-dev-shm-usage"
                    ));
            Browser browser = playwright.chromium().launch(launchOptions);
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            BrowserContext context = browser.newContext(contextOptions);
            Page page = context.newPage();

            page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(1200000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Step 2: Wait explicitly for the password field (or your critical UI element) to appear
            page.waitForSelector("#mpwd", new Page.WaitForSelectorOptions().setTimeout(200000));

            Locator passwordLocator = page.locator("#mpwd");
            passwordLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30000));
            passwordLocator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));

            // Fill credentials
            page.locator("#clientcode").fill(clientCode);
            page.locator("#password").fill(password);
            String totp = TOTPGenerator.generateCode(totpSecret);
            page.locator("#totp").fill(totp);

            // Submit
            page.click("button[type='submit']");

            // Wait for redirection and intercept final URL
            page.waitForURL("**access_token**", new Page.WaitForURLOptions().setTimeout(15000));
            String finalUrl = page.url();

            if (finalUrl.contains("accessToken")) {
                String[] parts = finalUrl.split("accessToken=");
                if (parts.length > 1) {
                    accessToken.set(parts[1].split("[&#]")[0]); // Handle ?accessToken=...&refreshToken=...
                }
            }

            browser.close();
        } catch (Exception e) {
            log.error("Error fetching Sharekhan token", e);
            throw new RuntimeException("Sharekhan token fetch failed: " + e.getMessage(), e);
        }

        return accessToken.get();
    }
}
