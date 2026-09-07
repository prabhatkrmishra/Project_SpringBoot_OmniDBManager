package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PgBouncer pooler configuration, bound from {@code app.pgbouncer.*}.
 * Managed defaults live here — only port/pool-mode/sizes are overrideable via
 * {@code OVERRIDE_PGBOUNCER_*}. Admin/stats users and config dir are hard constants.
 */
@ConfigurationProperties(prefix = "app.pgbouncer")
public record PgbouncerProperties(
        boolean enabled,
        int port,
        String poolMode,
        int maxClientConn,
        int defaultPoolSize,
        String adminPassword,
        String statsPassword) {

    public static final String ADMIN_USER = "pgbouncer_admin";
    public static final String STATS_USER = "pgbouncer_stats";
    public static final String CONFIG_DIR = "./pgbouncer";

    public String adminUser() {
        return ADMIN_USER;
    }

    public String statsUser() {
        return STATS_USER;
    }

    public String publicHost() {
        return "";
    }

    public String configDir() {
        return CONFIG_DIR;
    }
}
