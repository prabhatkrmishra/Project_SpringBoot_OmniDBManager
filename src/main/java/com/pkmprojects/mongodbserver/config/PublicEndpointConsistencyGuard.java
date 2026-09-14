package com.pkmprojects.mongodbserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fail-fast §55 validation for the single-host TLS-bridge proxy. The bridge
 * block is validated only when explicitly enabled so a half-configured
 * {@code database.proxy} can never start ambiguous routing.
 */
@Component
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true", matchIfMissing = false)
public class PublicEndpointConsistencyGuard implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PublicEndpointConsistencyGuard.class);
    private final DatabaseProxyProperties proxy;
    private final PgbouncerProperties pgbouncer;

    public PublicEndpointConsistencyGuard(DatabaseProxyProperties proxy,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PgbouncerProperties pgbouncer) {
        this.proxy = proxy;
        this.pgbouncer = pgbouncer;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (pgbouncer != null) {
            if (!"transaction".equalsIgnoreCase(pgbouncer.poolMode()))
                throw new IllegalStateException("app.pgbouncer.pool-mode must be transaction (got " + pgbouncer.poolMode() + ")");
            if (pgbouncer.defaultPoolSize() < 1 || pgbouncer.defaultPoolSize() > 25)
                throw new IllegalStateException("app.pgbouncer.default-pool-size out of sane range 1..25");
            if (pgbouncer.maxDbConnections() < 1)
                throw new IllegalStateException("app.pgbouncer.max-db-connections must be >= 1");
        }
        if (proxy == null || !proxy.enabled()) {
            log.info("DatabaseProxy disabled — legacy two-port strings stay authoritative (S-06 gate not passed)");
            return;
        }
        if (proxy.host() == null || proxy.host().isBlank())
            throw new IllegalStateException("database.proxy.host is required when proxy enabled");
        if (proxy.port() < 1 || proxy.port() > 65535)
            throw new IllegalStateException("database.proxy.port must be 1..65535");
        log.info("DatabaseProxy TLS-bridge public {}:{} (direct+pooled via options=-c omnidb.mode=; SCRAM is the access boundary)",
                proxy.normalizedHost(), proxy.port());
    }
}
