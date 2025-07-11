package org.com.sharekhan.util;


import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;


public class TOTPGenerator {

    // Generate a new Base32 encoded secret (store this securely)
    public static String generateSecret() {
        return Base32.random();
    }

    // Generate a TOTP based on secret and current system time
    public static String generateCode(String secret) {
        Totp totp = new Totp(secret);
        return totp.now();
    }

    // Validate TOTP input from user (optional)
    public static boolean verifyCode(String secret, String code) {
        Totp totp = new Totp(secret);
        return totp.verify(code);
    }
}
