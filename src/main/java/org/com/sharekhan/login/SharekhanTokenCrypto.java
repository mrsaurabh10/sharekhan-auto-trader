package org.com.sharekhan.login;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SharekhanTokenCrypto {

    private static final int GCM_TAG_LENGTH = 128;
    private static final byte[] IV = new byte[16]; // 16-byte zero IV

    public static String encrypt(String key, String data) throws Exception {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        if (raw.length != 32) throw new IllegalArgumentException("Invalid key size. Must be 32 bytes.");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec, new GCMParameterSpec(GCM_TAG_LENGTH, IV));

        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String key, String encrypted) throws Exception {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        if (raw.length != 32) throw new IllegalArgumentException("Invalid key size. Must be 32 bytes.");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        cipher.init(Cipher.DECRYPT_MODE, skeySpec, new GCMParameterSpec(GCM_TAG_LENGTH, IV));

        byte[] decoded = Base64.getDecoder().decode(encrypted);
        byte[] original = cipher.doFinal(decoded);
        return new String(original, StandardCharsets.UTF_8);
    }

    // Token Manipulation Step
    public static String generateManipulatedToken(String encryptedToken, String secretKey) throws Exception {
        String decrypted = decrypt(secretKey, encryptedToken);
        System.out.println("Decrypted: " + decrypted);
        String[] parts = decrypted.split("\\|");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid decrypted format.");
        String manipulated = parts[1] + "|" + parts[0]; // customerId|requestId
        return encrypt(secretKey, manipulated);
    }
}
