package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guard for tenant-password encryption at rest. Without
 * {@code APP_ENCRYPTION_KEY}, {@code storedPassword} persists in plaintext
 * (dev/test convenience) — previously with no warning at all, so a real
 * deployment could silently run unencrypted. Mirrors
 * {@link AdminCredentialsGuard}, which already fails fast on an explicit
 * operator opt-in rather than on a profile.
 *
 * <p>The {@code atlas} check alone was not enough: {@code atlas} names a storage
 * backend, not a deployment posture, and the documented systemd unit runs with
 * no Spring profile at all. So {@code APP_ENCRYPTION_ENFORCE} (default
 * {@code true}) is the real gate — only deployments that have a key are
 * unaffected, which is exactly the set that has nothing to fix. Never logs key
 * material.
 */
@Component
public class EncryptionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionGuard.class);

    private final EncryptionService encryptionService;
    private final EncryptionProperties encryptionProperties;
    private final Environment environment;

    public EncryptionGuard(EncryptionService encryptionService,
                           EncryptionProperties encryptionProperties,
                           Environment environment) {
        this.encryptionService = encryptionService;
        this.encryptionProperties = encryptionProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (encryptionService != null && encryptionService.isEnabled()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("atlas")) || encryptionProperties.enforce()) {
            throw new IllegalStateException(
                    "Refusing to start with tenant-password encryption disabled "
                            + "(no APP_ENCRYPTION_KEY). Set APP_ENCRYPTION_KEY in .env "
                            + "(openssl rand -base64 32).");
        }
        log.warn("Tenant-password encryption is disabled (no APP_ENCRYPTION_KEY) — "
                + "stored passwords persist in plaintext. Set APP_ENCRYPTION_KEY in .env for production.");
    }
}
