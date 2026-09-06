package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PgBouncer pooler configuration, bound from {@code app.pgbouncer.*}.
 * Mirrors the existing per-engine enable pattern (POSTGRES_ENABLED/MYSQL_ENABLED).
 *
 * <p>When {@code enabled=false} (default) no PgBouncer container, beans or UI
 * are registered — same as disabling MYSQL_ENABLED hides MySQL entirely.</p>
 */
@ConfigurationProperties(prefix = "app.pgbouncer")
public record PgbouncerProperties(
        boolean enabled,
        int port,
        String poolMode,
        int maxClientConn,
        int defaultPoolSize,
        String adminUser,
        String adminPassword,
        String statsUser,
        String statsPassword,
        String publicHost,
        String configDir) {
}
