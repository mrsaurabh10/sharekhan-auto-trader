package org.com.sharekhan.login;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class SharekhanLoginAutomation {

    String clientCode = "SGUPTA78";
    public static String password = "Anvi@2023";
    public static String totpSecret = "3VNUAS5GNB5UXCXR"; // Base32 TOTP Secret
    public static String secretKey = "iOpn2GrHHzmjdWu795RRw79d0OPZn7jh";
    public static String apiKey = "M57X7RqA9C43IOq8iJSySWv8LAD2DzkM";

    public static String fetchAccessToken() throws IOException, SharekhanAPIException {
        SharekhanConnect sharekhanConnect = new SharekhanConnect();
        String loginUrl = sharekhanConnect.getLoginURL(apiKey, null, "1234", 1234L);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(loginUrl, new Page.NavigateOptions()
                    .setTimeout(120000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Step 1: Fill password only (client code is pre-filled and disabled)
            page.locator("#mpwd").fill(password);
            page.locator("#lg_btn").click();

            // Step 2: Wait for TOTP field to appear and fill TOTP
            page.waitForSelector("#totp", new Page.WaitForSelectorOptions().setTimeout(8000));
            Totp totp = new Totp(totpSecret);
            String otpCode = totp.now();
            page.locator("#totp").fill(otpCode);

            // Step 3: Submit TOTP using button with specific onclick
            page.locator("button[onclick=\"submitOTP('TOTP')\"]").click();

            // Step 4: Wait for redirect with tokens
            page.waitForURL(url -> url.contains("test"), new Page.WaitForURLOptions().setTimeout(15000));
            String redirectedUrl = page.url();

            Map<String, String> tokens = extractTokensFromUrl(redirectedUrl);
            String rawToken = tokens.get("request_token");
            // This will replace `+` with `%2B`, `/` with `%2F`, etc.
            String safeToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

            return generateSession(sharekhanConnect, safeToken);
        }
    }

    public static Map<String, String> extractTokensFromUrl(String url) {
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
            System.err.println("Failed to parse tokens from URL: " + e.getMessage());
        }
        return result;
    }

    public static String generateSession(SharekhanConnect sharekhanConnect, String requestToken) throws SharekhanAPIException, IOException {
        Long state = 12345L;
        Long versionId = 1005L;
        JSONObject response = sharekhanConnect.generateSession(apiKey, requestToken, null, state, secretKey, versionId);
        return response.getJSONObject("data").getString("token");
    }
}
