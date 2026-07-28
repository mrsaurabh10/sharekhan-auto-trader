package org.com.sharekhan;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.ShoonyaProperties;
import org.jboss.aerogear.security.otp.Totp;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Browser automation equivalent to SharekhanTokenFetcher for Shoonya's OAuth login. */
@Component
@Slf4j
public class ShoonyaTokenFetcher {
    public String fetchAuthorizationCode(ShoonyaProperties properties, String authorizationUrl) {
        require(properties.getUid(), "uid"); require(properties.getPassword(), "password"); require(properties.getTotpSecret(), "totp-secret");
        log.info("Shoonya OAuth browser login started for configured UID");
        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true).setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu")))) {
            Page page = browser.newPage();
            page.navigate(authorizationUrl, new Page.NavigateOptions().setTimeout(120_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            // The OAuth page is a client-rendered app: DOMContentLoaded happens before its form controls appear.
            page.waitForSelector("input[type='text']", new Page.WaitForSelectorOptions().setTimeout(30_000));
            log.info("Shoonya OAuth login form loaded");
            Locator user = visible(page, List.of("#uid", "#user_id", "#userid", "input[name='uid']", "input[name='user_id']", "input[type='text']"));
            user.fill(properties.getUid());
            Locator passwordInputs = page.locator("input[type='password']");
            if (passwordInputs.count() >= 2) {
                // Shoonya's OAuth page presents user ID, password and TOTP together.
                passwordInputs.nth(0).fill(properties.getPassword());
                passwordInputs.nth(1).fill(currentTotp(properties.getTotpSecret()));
                click(visible(page, List.of("button:has-text('LOGIN')", "button[type='submit']", "input[type='submit']", "button")));
                log.info("Shoonya OAuth one-screen credentials submitted");
            } else {
                Locator password = visible(page, List.of("#password", "#pwd", "input[name='password']", "input[type='password']"));
                password.fill(properties.getPassword());
                click(visible(page, List.of("button[type='submit']", "input[type='submit']", "button:has-text('Login')", "button:has-text('Continue')", "button")));
                Locator totp = visible(page, List.of("#totp", "#otp", "input[name='totp']", "input[name='otp']", "input[autocomplete='one-time-code']"));
                totp.fill(currentTotp(properties.getTotpSecret()));
                click(visible(page, List.of("button[type='submit']", "input[type='submit']", "button:has-text('Verify')", "button:has-text('Submit')", "button")));
                log.info("Shoonya OAuth second-step TOTP submitted");
            }
            // The registered redirect is external (test.com); wait for the URL change, not for that page to finish loading.
            page.waitForURL(url -> url.contains("code="), new Page.WaitForURLOptions().setTimeout(60_000).setWaitUntil(WaitUntilState.COMMIT));
            String code = queryParameter(page.url(), "code");
            if (code == null || code.isBlank()) throw new IllegalStateException("Shoonya OAuth redirect did not include code");
            log.info("Shoonya OAuth authorization redirect received");
            return code;
        } catch (Exception e) {
            log.warn("Shoonya OAuth browser login stopped: {}", e.getMessage());
            throw new IllegalStateException("Shoonya browser OAuth login failed: " + e.getMessage(), e);
        }
    }
    private static Locator visible(Page page, List<String> selectors) {
        for (String selector : selectors) {
            Locator candidate = page.locator(selector);
            for (int index = 0; index < candidate.count(); index++) {
                Locator element = candidate.nth(index);
                if (element.isVisible()) return element;
            }
        }
        throw new IllegalStateException("Shoonya login page did not expose an expected input/button");
    }
    private static void click(Locator locator) { locator.click(new Locator.ClickOptions().setTimeout(30_000)); }
    private static String currentTotp(String secret) { return new Totp(secret.replaceAll("\\s", "")).now(); }
    private static String queryParameter(String url, String name) { try { String query = new URI(url).getRawQuery(); if (query == null) return null; for (String part : query.split("&")) { String[] pair = part.split("=", 2); if (pair.length == 2 && name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8); } return null; } catch (Exception e) { throw new IllegalStateException("Unable to parse Shoonya OAuth redirect", e); } }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalStateException("Shoonya " + name + " is not configured"); }
}
