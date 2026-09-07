package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PgBouncer pooler configuration, bound from {@code app.pgbouncer.*}.
 * Managed like pgvector — always on when Postgres is enabled, no enable flag.
 * Only port/pool-mode/sizes are overrideable via {@code OVERRIDE_PGBOUNCER_*}.
 * Admin/stats users are hard constants.
 */
@ConfigurationProperties(prefix = "app.pgbouncer")
public record PgbouncerProperties(
        int port,
        int issuedPort,
        String poolMode,
        int maxClientConn,
        int defaultPoolSize,
        String adminPassword,
        String statsPassword) {

    public static final String ADMIN_USER = "pgbouncer_admin";
    public static final String STATS_USER = "pgbouncer_stats";

    public String adminUser() {
        return ADMIN_USER;
    }

    public String statsUser() {
        return STATS_USER;
    }
}
