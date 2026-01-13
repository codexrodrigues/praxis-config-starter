package org.praxisplatform.config.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AiApiKeyCryptoService {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String PAYLOAD_PREFIX = "v1";

    @Value("${praxis.ai.api-key.encryption-key:#{null}}")
    private String base64Key;

    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKey cachedKey;
    private volatile boolean keyResolved;

    public boolean isEnabled() {
        return resolveKey() != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        SecretKey key = resolveKey();
        if (key == null) {
            throw new IllegalStateException("Encryption key not configured (praxis.ai.api-key.encryption-key)");
        }
        return encryptWithKey(plaintext, key);
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        SecretKey key = resolveKey();
        if (key == null) {
            log.warn("[AiApiKeyCrypto] Encryption key missing; cannot decrypt stored API key.");
            return null;
        }
        return decryptWithKey(encrypted, key);
    }

    public String encryptWithKey(String plaintext, String base64Key) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        SecretKey key = buildKey(base64Key);
        if (key == null) {
            throw new IllegalStateException("Encryption key not configured (praxis.ai.api-key.encryption-key)");
        }
        return encryptWithKey(plaintext, key);
    }

    public String decryptWithKey(String encrypted, String base64Key) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        SecretKey key = buildKey(base64Key);
        if (key == null) {
            log.warn("[AiApiKeyCrypto] Encryption key missing; cannot decrypt stored API key.");
            return null;
        }
        return decryptWithKey(encrypted, key);
    }

    private SecretKey resolveKey() {
        if (keyResolved) {
            return cachedKey;
        }
        synchronized (this) {
            if (keyResolved) {
                return cachedKey;
            }
            cachedKey = buildKey(base64Key);
            keyResolved = true;
            return cachedKey;
        }
    }

    private SecretKey buildKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid base64 for praxis.ai.api-key.encryption-key", ex);
        }
        int len = decoded.length;
        if (len != 16 && len != 24 && len != 32) {
            throw new IllegalArgumentException("Encryption key must be 16/24/32 bytes (base64-encoded)");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private String encryptWithKey(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PAYLOAD_PREFIX
                    + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt API key", e);
        }
    }

    private String decryptWithKey(String encrypted, SecretKey key) {
        String[] parts = encrypted.split(":", 3);
        if (parts.length != 3 || !PAYLOAD_PREFIX.equals(parts[0])) {
            log.warn("[AiApiKeyCrypto] Unsupported encrypted payload format.");
            return null;
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[AiApiKeyCrypto] Failed to decrypt API key.", e);
            return null;
        }
    }
}
