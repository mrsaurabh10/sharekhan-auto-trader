package org.com.sharekhan.util;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    @Value("${app.crypto.key:}")
    private String base64Key;

    private SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();
    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12; // bytes

    @PostConstruct
    public void init() {
        if (base64Key == null || base64Key.isBlank()) {
            // generate a temporary 256-bit key to allow startup; warn the operator
            byte[] tempKey = new byte[32];
            secureRandom.nextBytes(tempKey);
            base64Key = Base64.getEncoder().encodeToString(tempKey);
            log.warn("⚠️ app.crypto.key not set — generated a temporary key for this JVM session. " +
                    "Set 'app.crypto.key' in application.yml or APP_CRYPTO_KEY env var to persist encrypted data across restarts.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decode app.crypto.key — ensure it's base64-encoded AES key", e);
        }
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("Invalid AES key length. Provide 128/192/256-bit key base64 encoded.");
        }
        keySpec = new SecretKeySpec(keyBytes, AES);
        log.info("🔐 CryptoService initialized (key length: {} bits)", keyBytes.length * 8);
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String cipherTextB64) {
        if (cipherTextB64 == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(cipherTextB64);
            if (all.length < IV_LENGTH) return null;
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
