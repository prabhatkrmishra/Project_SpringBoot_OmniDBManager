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
 *
 *                Note this component is a primitive, so binding it while the
 *                property is absent yields {@code false}, not {@code true}. The
 *                secure default therefore lives in application.yml
 *                ({@code enforce: ${APP_ENCRYPTION_ENFORCE:true}}), not here. If
 *                that key is ever renamed or the file is bypassed, enforcement
 *                silently switches off — worth remembering when touching either.
 */
// Exactly one constructor, deliberately. @ConfigurationPropertiesScan binds this
// by constructor, and adding a second one leaves the binder unable to choose --
// it falls back to JavaBean instantiation and fails with "No default constructor
// found", which takes the entire application down at startup. Do not add an
// overload; pass the second argument explicitly instead.
@ConfigurationProperties(prefix = "app.encryption")
public record EncryptionProperties(String key, boolean enforce) {
}
