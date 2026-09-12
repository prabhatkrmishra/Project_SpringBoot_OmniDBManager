package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-port Database Proxy reservation for the §3 target architecture.
 * <b>Config-only in this change — no cutover.</b> Nginx two-port operation
 * continues until S-05 spike evidence + prod {@code direct/pooled-hostname},
 * {@code :15432} cert and NSG single-port approval land (S-06 gate).
 */
@ConfigurationProperties(prefix = "database.proxy")
public record DatabaseProxyProperties(
        boolean enabled,
        int port,
        String directHost,
        String pooledHost) {
    public DatabaseProxyProperties {
        if (port == 0) port = 15432;
    }

    public boolean isConfigured() {
        return enabled && directHost != null && !directHost.isBlank()
                && pooledHost != null && !pooledHost.isBlank()
                && !directHost.trim().equalsIgnoreCase(pooledHost.trim());
    }
}
