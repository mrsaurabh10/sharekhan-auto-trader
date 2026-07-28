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
        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true).setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu")))) {
            Page page = browser.newPage();
            page.navigate(authorizationUrl, new Page.NavigateOptions().setTimeout(120_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Locator user = visible(page, List.of("#uid", "#user_id", "#userid", "input[name='uid']", "input[name='user_id']", "input[type='text']"));
            user.fill(properties.getUid());
            Locator password = visible(page, List.of("#password", "#pwd", "input[name='password']", "input[type='password']"));
            password.fill(properties.getPassword());
            click(visible(page, List.of("button[type='submit']", "input[type='submit']", "button:has-text('Login')", "button:has-text('Continue')")));
            Locator totp = visible(page, List.of("#totp", "#otp", "input[name='totp']", "input[name='otp']", "input[autocomplete='one-time-code']"));
            totp.fill(new Totp(properties.getTotpSecret()).now());
            click(visible(page, List.of("button[type='submit']", "input[type='submit']", "button:has-text('Verify')", "button:has-text('Submit')")));
            page.waitForURL(url -> url.contains("code="), new Page.WaitForURLOptions().setTimeout(120_000));
            String code = queryParameter(page.url(), "code");
            if (code == null || code.isBlank()) throw new IllegalStateException("Shoonya OAuth redirect did not include code");
            return code;
        } catch (Exception e) { throw new IllegalStateException("Shoonya browser OAuth login failed: " + e.getMessage(), e); }
    }
    private static Locator visible(Page page, List<String> selectors) { for (String selector : selectors) { Locator candidate = page.locator(selector); if (candidate.count() > 0 && candidate.first().isVisible()) return candidate.first(); } throw new IllegalStateException("Shoonya login page did not expose an expected input/button"); }
    private static void click(Locator locator) { locator.click(new Locator.ClickOptions().setTimeout(30_000)); }
    private static String queryParameter(String url, String name) { try { String query = new URI(url).getRawQuery(); if (query == null) return null; for (String part : query.split("&")) { String[] pair = part.split("=", 2); if (pair.length == 2 && name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8); } return null; } catch (Exception e) { throw new IllegalStateException("Unable to parse Shoonya OAuth redirect", e); } }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalStateException("Shoonya " + name + " is not configured"); }
}
