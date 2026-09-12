package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-port Database Proxy (plain TCP passthrough to the pooler).
 * Two shapes, both on one public port (default {@code :14291}):
 * <ul>
 *   <li><b>Dual-link</b> — {@code direct-host} + {@code pooled-host} set and
 *       different: {@code direct-host} → PostgreSQL, {@code pooled-host} →
 *       PgBouncer.</li>
 *   <li><b>Pooled-only public</b> — only {@code pooled-host} set: the public
 *       port serves the pooled link; direct stays on the internal address
 *       (loopback on-box, SSH tunnel from outside). There is deliberately no
 *       same-hostname split: SNI routing cannot distinguish two links behind
 *       one name.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "database.proxy")
public record DatabaseProxyProperties(
        boolean enabled,
        int port,
        String directHost,
        String pooledHost) {
    public DatabaseProxyProperties {
        if (port == 0) port = 14291;
    }

    /** Proxy serves at least the pooled link (pooled-only or dual). */
    public boolean isConfigured() {
        return enabled && pooledHost != null && !pooledHost.isBlank();
    }

    /** Public port serves pooled only; direct has no public route. */
    public boolean isPooledOnlyPublic() {
        return isConfigured() && (directHost == null || directHost.isBlank());
    }

    /** Direct link goes through the proxy only in dual-link shape. */
    public boolean directViaProxy() {
        return isConfigured() && !isPooledOnlyPublic();
    }
}
