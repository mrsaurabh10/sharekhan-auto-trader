package org.com.sharekhan.login;

import org.com.sharekhan.SharekhanTokenFetcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WARNING: This is a real integration test.
 * It opens a browser using Playwright, performs actual login,
 * and fetches a live access token from Sharekhan.
 *
 * ⚠️ Requires working credentials, network access, and valid TOTP secret.
 */
class SharekhanLoginIntegrationTest {

    @Test
    void fetchAccessTokenWithPlaywright() {
        String clientCode = System.getenv("SHAREKHAN_CLIENT_CODE");
        String password = System.getenv("SHAREKHAN_PASSWORD");
        String totpSecret = System.getenv("SHAREKHAN_TOTP_SECRET");
        String apiKey = System.getenv("SHAREKHAN_API_KEY");
        String secretKey = System.getenv("SHAREKHAN_SECRET_KEY");

        Assumptions.assumeTrue(clientCode != null && !clientCode.isBlank(), "Set SHAREKHAN_CLIENT_CODE");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "Set SHAREKHAN_PASSWORD");
        Assumptions.assumeTrue(totpSecret != null && !totpSecret.isBlank(), "Set SHAREKHAN_TOTP_SECRET");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "Set SHAREKHAN_API_KEY");
        Assumptions.assumeTrue(secretKey != null && !secretKey.isBlank(), "Set SHAREKHAN_SECRET_KEY");

        SharekhanTokenFetcher fetcher = new SharekhanTokenFetcher();
        String token = fetcher.fetchAccessToken(clientCode, password, totpSecret, apiKey, secretKey);

        assertNotNull(token, "Token should not be null");
       assertFalse(token.isBlank(), "Token should not be blank");
       assertTrue(token.length() > 100, "Token looks unexpectedly short");
        System.out.println("🔐 Sharekhan access token: " + token);
        System.out.println("✅ Sharekhan access token fetched successfully.");
    }
}
