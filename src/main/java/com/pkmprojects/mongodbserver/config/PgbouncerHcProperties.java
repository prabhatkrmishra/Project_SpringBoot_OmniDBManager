package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S-14 high-concurrency PgBouncer instance, bound from {@code app.pgbouncer-hc.*}.
 *
 * <p>Dedicated pooler for {@code profile=high_concurrency}: transaction pooling
 * with the same authentication model (auth_user + auth_query/user_lookup),
 * same reset semantics (DISCARD ALL), and larger but explicitly capped per-db
 * sizing. The standard instance ({@link PgbouncerProperties}) is untouched.
 *
 * <p>Internal identity is {@code pgbouncer-hc:6433}; the port is never
 * published externally — the Go TLS bridge is the only public entrypoint
 * ({@code db.example.com:15432}). Pool sizing here is a starting policy, not
 * an unlimited budget: aggregate PostgreSQL backends across both instances
 * must respect {@code max_connections} (see OPERATOR-RECOVERY capacity notes).
 *
 * <p>NOTE: single canonical constructor only (see {@link PgbouncerProperties}
 * for why overloads break record binding).
 */
@ConfigurationProperties(prefix = "app.pgbouncer-hc")
public record PgbouncerHcProperties(
        int port,
        int maxClientConn,
        int defaultPoolSize,
        int reservePoolSize,
        int reservePoolTimeout,
        int maxDbConnections,
        String adminPassword,
        String statsPassword,
        String authPassword) {

    /** Internal service name; never exposed to end users. */
    public static final String SERVICE = "pgbouncer-hc";
    /** Internal port; never published externally. */
    public static final int DEFAULT_PORT = 6433;

    public String adminUser() {
        return PgbouncerProperties.ADMIN_USER;
    }

    public String statsUser() {
        return PgbouncerProperties.STATS_USER;
    }

    public String authUser() {
        return PgbouncerProperties.AUTH_USER;
    }

    public PgbouncerHcProperties {
        if (port == 0) {
            port = DEFAULT_PORT;
        } else if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("app.pgbouncer-hc.port must be between 1 and 65535, got " + port);
        }
    }
}
