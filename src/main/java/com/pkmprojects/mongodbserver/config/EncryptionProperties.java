package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AES-256-GCM key for encrypting {@code ManagedDatabase.storedPassword} at rest.
 * Bind from {@code app.encryption.key} / {@code APP_ENCRYPTION_KEY} (base64 32 bytes).
 * When blank, encryption is disabled (dev/test) and passwords are stored plaintext.
 */
@ConfigurationProperties(prefix = "app.encryption")
public record EncryptionProperties(String key) {
}
