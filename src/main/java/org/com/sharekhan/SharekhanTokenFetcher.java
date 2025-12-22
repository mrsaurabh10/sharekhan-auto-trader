package org.com.sharekhan;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ViewportSize;
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

            // Allow engine override by env (chromium|firefox|webkit)
            String engine = System.getenv().getOrDefault("SK_ENGINE", "chromium").toLowerCase();
            boolean lightMode = Boolean.parseBoolean(System.getenv().getOrDefault("SK_LIGHT_MODE", "true"));

            BrowserType browserType =
                    "firefox".equals(engine) ? playwright.firefox() :
                    ("webkit".equals(engine) ? playwright.webkit() : playwright.chromium());

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList(
                            "--single-process",
                            "--no-zygote",
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-gpu",
                            "--disable-extensions",
                            "--disable-background-networking",
                            "--disable-popup-blocking",
                            "--disable-default-apps",
                            "--disable-sync",
                            "--no-first-run",
                            "--no-default-browser-check",
                            "--metrics-recording-only",
                            "--mute-audio",
                            "--hide-scrollbars",
                            "--disable-features=Translate,BackForwardCache,AcceptCHFrame,MediaRouter,OptimizationHints,AutofillServerCommunication,InterestFeed,ComputePressure",
                            "--disable-blink-features=AutomationControlled",
                            // Skip image decoding to save CPU/RAM
                            "--blink-settings=imagesEnabled=false",
                            // In containers / small SHM
                            "--disable-dev-shm-usage"
                    ));

            Browser browser = browserType.launch(launchOptions);
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setViewportSize(new ViewportSize(800, 600))
                    .setDeviceScaleFactor(1);

            BrowserContext context = browser.newContext(contextOptions);

            // Block heavy resources to reduce CPU/memory
            context.route("**/*", route -> {
                try {
                    String type = route.request().resourceType();
                    String url = route.request().url();
                    if ("image".equals(type) || "media".equals(type) || "font".equals(type)
                            || (lightMode && ("stylesheet".equals(type) || url.contains("/analytics")))) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                } catch (Exception e) {
                    route.resume();
                }
            });

            Page page = context.newPage();
            page.setDefaultTimeout(20_000);
            page.setDefaultNavigationTimeout(25_000);
            // Remove animations/transitions to cut layout work
            page.addInitScript("try{const s=document.createElement('style');s.innerHTML='*{animation:none!important;transition:none!important}';document.head.appendChild(s);}catch(e){}");

            page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(120000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Step 2: Wait explicitly for the password field (or your critical UI element) to appear
            page.waitForSelector("#mpwd", new Page.WaitForSelectorOptions().setTimeout(10000));

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
