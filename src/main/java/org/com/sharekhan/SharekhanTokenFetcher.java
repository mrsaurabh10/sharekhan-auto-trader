package org.com.sharekhan;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import org.com.sharekhan.util.TOTPGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class SharekhanTokenFetcher {

    private static final String LOGIN_URL = "https://www.sharekhan.com/login";
    private static final String REDIRECT_URL_CONTAINS = "accessToken";

    public String fetchAccessToken(String clientCode, String password, String totpSecret) {
        AtomicReference<String> accessToken = new AtomicReference<>(null);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

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
