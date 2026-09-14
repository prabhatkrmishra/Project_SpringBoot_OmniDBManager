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
 * {@link AdminCredentialsGuard}: always warn when disabled, and refuse to
 * start under the {@code atlas} profile. Never logs key material.
 */
@Component
public class EncryptionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionGuard.class);

    private final EncryptionService encryptionService;
    private final Environment environment;

    public EncryptionGuard(EncryptionService encryptionService, Environment environment) {
        this.encryptionService = encryptionService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (encryptionService != null && encryptionService.isEnabled()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("atlas"))) {
            throw new IllegalStateException(
                    "Refusing to start with tenant-password encryption disabled "
                            + "(no APP_ENCRYPTION_KEY). Set APP_ENCRYPTION_KEY in .env "
                            + "(openssl rand -base64 32).");
        }
        log.warn("Tenant-password encryption is disabled (no APP_ENCRYPTION_KEY) — "
                + "stored passwords persist in plaintext. Set APP_ENCRYPTION_KEY in .env for production.");
    }
}
