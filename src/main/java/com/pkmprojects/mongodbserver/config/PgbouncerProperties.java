package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PgBouncer pooler configuration, bound from {@code app.pgbouncer.*}.
 * Managed like pgvector — always on when Postgres is enabled, no enable flag.
 * Only port/pool-mode/sizes are overrideable via {@code OVERRIDE_PGBOUNCER_*}.
 * Admin/stats/auth users are hard constants; their passwords come from env.
 */
@ConfigurationProperties(prefix = "app.pgbouncer")
public record PgbouncerProperties(
        int port,
        int issuedPort,
        String poolMode,
        int maxClientConn,
        int defaultPoolSize,
        int reservePoolSize,
        int reservePoolTimeout,
        int maxDbConnections,
        String adminPassword,
        String statsPassword,
        String authPassword) {

    public static final String ADMIN_USER = "pgbouncer_admin";
    public static final String STATS_USER = "pgbouncer_stats";
    /**
     * Least-privilege role the pooler logs in as to run {@code auth_query}.
     * Has LOGIN + CONNECT on pooled databases + EXECUTE on the lookup
     * function only — never superuser, never a table grant.
     */
    public static final String AUTH_USER = "pgbouncer_auth";

    public String adminUser() {
        return ADMIN_USER;
    }

    public String statsUser() {
        return STATS_USER;
    }

    /** Compact canonical constructor keeps backward-compat for 8-arg test dtype. */
    public PgbouncerProperties {
        if (poolMode == null || poolMode.isBlank()) poolMode = "transaction";
    }

    /** Legacy 8-arg shape used by older tests/config — fills conservative §17 defaults. */
    public PgbouncerProperties(int port, int issuedPort, String poolMode, int maxClientConn,
            int defaultPoolSize, String adminPassword, String statsPassword, String authPassword) {
        this(port, issuedPort, poolMode, maxClientConn, defaultPoolSize,
                2, 3, 10, adminPassword, statsPassword, authPassword);
    }
}
