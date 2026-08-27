package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.EncryptionProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for {@code storedPassword} at rest. Key from
 * {@code APP_ENCRYPTION_KEY} (base64 32 bytes). When no key is configured,
 * encryption is a no-op (dev/test). Format: {@code ENC:v1:<base64 iv+ciphertext+tag>}.
 */
@Service
public class EncryptionService {

    private static final String PREFIX = "ENC:v1:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final byte[] key;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(EncryptionProperties props) {
        String raw = props != null ? props.key() : null;
        if (raw == null || raw.isBlank()) {
            this.key = null;
            this.enabled = false;
            org.slf4j.LoggerFactory.getLogger(EncryptionService.class)
                    .warn("APP_ENCRYPTION_KEY not set - stored passwords will be plaintext (dev only)");
            return;
        }
        String trimmed = raw.trim();
        byte[] decoded;
        // Hex is unambiguous (64 hex chars); check first before base64 which would also decode hex chars
        if (trimmed.matches("[0-9a-fA-F]{64}")) {
            decoded = hexToBytes(trimmed);
        } else {
            try {
                decoded = Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must be base64 (32 bytes) or 64 hex chars", e);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to 32 bytes (got " + decoded.length + ")");
            }
        }
        this.key = decoded;
        this.enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        if (!enabled) return plaintext;
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext or encryption disabled
            return stored;
        }
        if (!enabled) {
            throw new IllegalStateException("Cannot decrypt: APP_ENCRYPTION_KEY not configured");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length < IV_LEN + 1) {
                throw new IllegalStateException("Invalid encrypted payload: too short");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
