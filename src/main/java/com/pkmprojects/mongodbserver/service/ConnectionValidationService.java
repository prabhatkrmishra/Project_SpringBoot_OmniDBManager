package com.pkmprojects.mongodbserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * End-to-end tenant validation: connect with tenant credentials and run
 * {@code SELECT 1}. Exercises the real PgBouncer route for pooled (not just
 * {@code SHOW POOLS}) and the direct route for migrations. Until the
 * single-port proxy lands, pooled validation uses the internal-equivalent
 * pooled address; the same method will target {@code pool.host:15432} after
 * S-06 without caller changes.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true", matchIfMissing = false)
public class ConnectionValidationService {
    private static final Logger log = LoggerFactory.getLogger(ConnectionValidationService.class);
    private final PostgresDatabaseEngine engine;
    private final PostgresConnectionStringBuilder strings;

    public ConnectionValidationService(PostgresDatabaseEngine engine, PostgresConnectionStringBuilder strings) {
        this.engine = engine;
        this.strings = strings;
    }

    public boolean validateDirect(String dbName, String user, String password) {
        return run("direct", engine.connectionEndpoints(dbName, user, password, false).direct());
    }

    public boolean validatePooled(String dbName, String user, String password) {
        var conns = engine.connectionEndpoints(dbName, user, password, true);
        if (conns.pooled() == null) return false;
        return run("pooled", conns.pooled());
    }

    private boolean run(String mode, com.pkmprojects.mongodbserver.model.ConnectionEndpoint ep) {
        String jdbc = strings.toJdbc(ep);
        // Never log the URI (contains password) — host/db/mode only.
        // DriverManager keeps this compile-safe: the PG driver is a
        // runtime-scoped dep (no org.postgresql.* imports allowed here).
        try (var c = java.sql.DriverManager.getConnection(jdbc);
                var st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (var rs = st.executeQuery("SELECT 1")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            log.debug("ConnectionValidationService {} validation failed for db={} host={}", mode, ep.database(), ep.host(), e);
            return false;
        }
    }

    /** Health pair preserving DIRECT/POOLED independence (§31/§57). */
    public record EndpointHealth(boolean directHealthy, boolean pooledHealthy) {
    }

    public EndpointHealth health(String dbName, String user, String password, boolean pooledEnabled) {
        boolean d = validateDirect(dbName, user, password);
        boolean p = pooledEnabled && validatePooled(dbName, user, password);
        return new EndpointHealth(d, p);
    }

}
