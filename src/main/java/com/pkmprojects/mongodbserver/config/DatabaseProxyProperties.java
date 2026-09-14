package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-host TLS-bridge Database Proxy (S-06 final).
 *
 * <p>One public hostname + one public port serve BOTH modes:
 * {@code db.example.com:15432} with {@code options=-c omnidb.mode=direct}
 * routes to PostgreSQL and {@code options=-c omnidb.mode=pooled} routes to
 * PgBouncer. SNI alone cannot split one hostname, so the mode comes from the
 * StartupMessage {@code options} field after client TLS termination.
 * PostgreSQL stays the auth authority; the proxy never stores passwords.
 */
@ConfigurationProperties(prefix = "database.proxy")
public record DatabaseProxyProperties(
        boolean enabled,
        int port,
        String host) {
    public DatabaseProxyProperties {
        if (port == 0) port = 15432;
    }

    /** Proxy serves the single public endpoint when enabled with a host. */
    public boolean isConfigured() {
        return enabled && host != null && !host.isBlank();
    }

    /** Normalized public hostname (trimmed, never null). */
    public String normalizedHost() {
        return host == null ? "" : host.trim();
    }
}
