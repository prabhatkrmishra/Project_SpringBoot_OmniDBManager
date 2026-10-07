package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AES-256-GCM key for encrypting {@code ManagedDatabase.storedPassword} at rest.
 * Bind from {@code app.encryption.key} / {@code APP_ENCRYPTION_KEY} (base64 32 bytes).
 * When blank, encryption is disabled (dev/test) and passwords are stored plaintext.
 *
 * @param key     base64 32 bytes, or 64 hex chars
 * @param enforce when true, refuse to start if the key is missing, regardless of
 *                the active profile. Unlike the {@code atlas} profile this does not
 *                depend on a profile being selected, because the documented
 *                deployment runs with no profile at all.
 */
@ConfigurationProperties(prefix = "app.encryption")
public record EncryptionProperties(String key, boolean enforce) {

    /** Key-only construction; enforcement defaults on, matching application.yml. */
    public EncryptionProperties(String key) {
        this(key, true);
    }
}
