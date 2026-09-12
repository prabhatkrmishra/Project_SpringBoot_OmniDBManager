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
 * pooled address; the same method targets {@code pooled-host:14291} once
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
        return validatePooledDetailed(dbName, user, password).healthy();
    }

    /**
     * Tiered pooled proof (§29 + review hierarchy). {@code PUBLIC} exercises
     * the full app contract (DNS → NSG → proxy :14291 → SNI → pooler → PG);
     * {@code LOOPBACK} proves only tenant → pooler → PG with tenant SCRAM
     * (same pooler, same {@code auth_query}) — necessary but <b>not
     * equivalent</b> to the production path. Callers must log the path and
     * only the {@code PUBLIC} tier proves the complete contract after S-06.
     */
    public PooledValidation validatePooledDetailed(String dbName, String user, String password) {
        var conns = engine.connectionEndpoints(dbName, user, password, true);
        if (conns.pooled() == null) return new PooledValidation(false, ValidationPath.NONE);
        if (run("pooled", conns.pooled())) return new PooledValidation(true, ValidationPath.PUBLIC);
        var loopback = new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                "127.0.0.1:" + conns.pooled().port(), conns.pooled().port(),
                dbName, user, password, conns.pooled().sslMode(),
                com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED,
                com.pkmprojects.mongodbserver.model.PoolMode.TRANSACTION);
        if (run("pooled-loopback", loopback)) return new PooledValidation(true, ValidationPath.LOOPBACK);
        return new PooledValidation(false, ValidationPath.NONE);
    }

    /** Which tier proved pooled health — see {@link #validatePooledDetailed}. */
    public enum ValidationPath {
        /** Full public route (DNS → proxy :14291 → SNI → pooler → PG). */
        PUBLIC,
        /** Same pooler + auth_query + tenant SCRAM via loopback; proxy hop unproven. */
        LOOPBACK,
        /** No tier proved health. */
        NONE
    }

    public record PooledValidation(boolean healthy, ValidationPath path) {
    }

    /** Protected so tests can stub tiers without live PostgreSQL. */
    protected boolean run(String mode, com.pkmprojects.mongodbserver.model.ConnectionEndpoint ep) {
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
