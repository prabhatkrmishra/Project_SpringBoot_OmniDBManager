package com.pkmprojects.mongodbserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the admin account. The defaults in {@code application.yml}
 * ({@code admin}/{@code admin}) exist for local convenience; running with them
 * against a real deployment (the {@code atlas} profile) is a credential risk, so
 * startup fails instead of silently exposing well-known credentials.
 */
@Component
public class AdminCredentialsGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialsGuard.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

    private final AdminProperties adminProperties;
    private final Environment environment;

    public AdminCredentialsGuard(AdminProperties adminProperties, Environment environment) {
        this.adminProperties = adminProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean defaultUsername = DEFAULT_USERNAME.equals(adminProperties.username());
        boolean defaultPassword = DEFAULT_PASSWORD.equals(adminProperties.password());
        if (!defaultUsername && !defaultPassword) {
            return;  // Both are custom, OK
        }

        // One or both are default — enforce under the atlas profile, or always
        // when the operator has explicitly opted into strict enforcement.
        boolean isAtlasProfile = environment.acceptsProfiles(Profiles.of("atlas"));
        if (isAtlasProfile || adminProperties.enforceStrongCredentials()) {
            // Never embed the password value in the message — it would land in
            // startup failure logs and crash reports.
            throw new IllegalStateException(
                    "Refusing to start with default admin credentials "
                            + "(username='" + adminProperties.username() + "', password='****'). "
                            + "Set APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD in .env.");
        }

        log.warn("Starting with default admin credentials (username='{}'). "
                + "Consider setting APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD in .env for production.",
                adminProperties.username());
    }
}
