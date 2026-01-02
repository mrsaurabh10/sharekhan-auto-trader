package org.com.sharekhan.login;

import com.sharekhan.SharekhanConnect;
import com.sharekhan.http.exceptions.SharekhanAPIException;
import org.com.sharekhan.service.ScriptMasterCacheService;
import org.json.JSONObject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WARNING: This is a real integration test.
 * It opens a browser using Playwright, performs actual login,
 * and fetches a live access token from Sharekhan.
 *
 * ⚠️ Requires working credentials, network access, and valid TOTP secret.
 */
class SharekhanLoginIntegrationTest {

    //@Test
//    @Disabled("Enable only for live integration testing")
//    void testFetchAccessTokenLive() {
//        try {
//            String token = SharekhanLoginAutomation.fetchAccessToken();
//            assertNotNull(token);
//            assertTrue(token.length() > 100, "Access token looks too short");
//            System.out.println("✅ Fetched live token: " + token);
//        } catch (IOException | SharekhanAPIException e) {
//            fail("Exception during fetchAccessToken: " + e.getMessage());
//        }
//    }

//    @Test
//    @Disabled("Enable only for live integration testing")
//    void testFetchAccessTokenLiveAndFetchScripts() {
//        try {
//            // Integration with ScriptMasterCacheService
//            SharekhanConnect sdk = new SharekhanConnect();
//            ScriptMasterCacheService service = new ScriptMasterCacheService(sdk);
//
//            Map<String, JSONObject> scriptMap = service.getScriptCache("NC");
//            assertFalse(scriptMap.isEmpty(), "Script cache should not be empty");
//
//            scriptMap.entrySet().stream().limit(5).forEach(e ->
//                    System.out.println(e.getKey() + " => " + e.getValue().toString(2)));
//
//        } catch (IOException | SharekhanAPIException e) {
//            fail("Exception during integration test: " + e.getMessage());
//        }
//    }
}
