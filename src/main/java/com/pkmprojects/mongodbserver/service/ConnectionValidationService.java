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
 * pooled address; the same method targets the single-host bridge
 * without caller changes once the bridge cutover lands.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true", matchIfMissing = false)
public class ConnectionValidationService {
    private static final Logger log = LoggerFactory.getLogger(ConnectionValidationService.class);
    private final PostgresDatabaseEngine engine;
    private final PostgresConnectionStringBuilder strings;
    private volatile com.pkmprojects.mongodbserver.config.PgbouncerHcProperties hcProperties;

    public ConnectionValidationService(PostgresDatabaseEngine engine, PostgresConnectionStringBuilder strings) {
        this.engine = engine;
        this.strings = strings;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHcProperties(com.pkmprojects.mongodbserver.config.PgbouncerHcProperties hcProperties) {
        this.hcProperties = hcProperties;
    }

    boolean isHcConfigured() {
        return hcProperties != null;
    }

    int hcLoopbackPort() {
        return hcProperties != null ? hcProperties.port()
                : com.pkmprojects.mongodbserver.config.PgbouncerHcProperties.DEFAULT_PORT;
    }

    public boolean validateDirect(String dbName, String user, String password) {
        return run("direct", engine.connectionEndpoints(dbName, user, password, false).direct());
    }

    public boolean validatePooled(String dbName, String user, String password) {
        return validatePooledDetailed(dbName, user, password).healthy();
    }

    /**
     * Tiered pooled proof (§29 + review hierarchy). {@code PUBLIC} exercises
     * the full app contract (DNS → NSG → bridge :15432 → pooler → PG);
     * {@code LOOPBACK} proves only tenant → pooler → PG with tenant SCRAM
     * (same pooler, same {@code auth_query}) — necessary but <b>not
     * equivalent</b> to the production path. Callers must log the path and
     * only the {@code PUBLIC} tier proves the complete contract once the bridge cutover lands.
     */
    public PooledValidation validatePooledDetailed(String dbName, String user, String password) {
        var conns = engine.connectionEndpoints(dbName, user, password, true);
        if (conns.pooled() == null) return new PooledValidation(false, ValidationPath.NONE);
        if (run("pooled", conns.pooled())) return new PooledValidation(true, ValidationPath.PUBLIC);
        // Loopback sslmode must match pooler reality, not the public contract:
        // proxy on => pooler terminates client TLS (require); proxy off =>
        // pooler is plaintext (two-port TLS-termination model) so require
        // would fail closed against 127.0.0.1:6432. Same-host hop — no
        // security lost; SCRAM + auth_query are still fully exercised.
        // The loopback hop bypasses the proxy, so it must use the plain JDBC
        // (no bridge options=) even when the public endpoint is bridged.
        var loopbackSsl = engine.isProxyMode()
                ? conns.pooled().sslMode()
                : com.pkmprojects.mongodbserver.model.SslMode.DISABLE;
        var loopback = new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                "127.0.0.1:" + conns.pooled().port(), conns.pooled().port(),
                dbName, user, password, loopbackSsl,
                com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED,
                com.pkmprojects.mongodbserver.model.PoolMode.TRANSACTION);
        if (runLoopback(loopback)) return new PooledValidation(true, ValidationPath.LOOPBACK);
        return new PooledValidation(false, ValidationPath.NONE);
    }

    /**
     * High-concurrency validation (standard is the mandatory gate; the HC route is also
     * exercised when the HC pooler is enabled). PUBLIC exercises the full
     * bridge path with {@code profile=high_concurrency}; LOOPBACK proves the
     * HC pooler + auth_query + tenant SCRAM via {@code 127.0.0.1:<hc-port>}
     * bypassing the bridge. HC failure never fails provisioning — it is
     * logged as capability health (standard remains the gate) — but HC
     * support is never claimed from standard validation alone.
     */
    public PooledValidation validateHcDetailed(String dbName, String user, String password) {
        if (!isHcConfigured()) return new PooledValidation(false, ValidationPath.NONE);
        var conns = engine.connectionEndpoints(dbName, user, password, true);
        if (conns.pooled() == null) return new PooledValidation(false, ValidationPath.NONE);
        if (runHcPublic(conns.pooled())) return new PooledValidation(true, ValidationPath.PUBLIC);
        var loopbackSsl = engine.isProxyMode()
                ? conns.pooled().sslMode()
                : com.pkmprojects.mongodbserver.model.SslMode.DISABLE;
        var loopback = new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                "127.0.0.1:" + hcLoopbackPort(), hcLoopbackPort(),
                dbName, user, password, loopbackSsl,
                com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED,
                com.pkmprojects.mongodbserver.model.PoolMode.TRANSACTION);
        if (runLoopback(loopback)) return new PooledValidation(true, ValidationPath.LOOPBACK);
        return new PooledValidation(false, ValidationPath.NONE);
    }

    boolean runHcPublic(com.pkmprojects.mongodbserver.model.ConnectionEndpoint standardPooledEp) {
        String jdbc;
        if (engine.isProxyMode()) {
            jdbc = strings.toJdbcBridged(standardPooledEp,
                    com.pkmprojects.mongodbserver.model.PoolProfile.HIGH_CONCURRENCY);
        } else {
            // No bridge: public HC is the HC pooler port directly (legacy
            // two-port model extended). Never leak bridge options= here.
            // Host mirrors resolvePooledHost with the port swapped to the HC
            // port (same DNS host, never 127.0.0.1 in prod).
            String hcHost = engine.resolvePooledHost();
            int colon = hcHost.lastIndexOf(':');
            String hostOnly = colon >= 0 ? hcHost.substring(0, colon) : hcHost;
            var hcEp = new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                    hostOnly + ":" + hcLoopbackPort(), hcLoopbackPort(),
                    standardPooledEp.database(), standardPooledEp.username(), standardPooledEp.password(),
                    standardPooledEp.sslMode(), standardPooledEp.mode(), standardPooledEp.poolMode());
            jdbc = strings.toJdbc(hcEp);
        }
        try (var c = java.sql.DriverManager.getConnection(jdbc);
                var st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (var rs = st.executeQuery("SELECT 1")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            log.debug("ConnectionValidationService hc-public validation failed for db={} host={}",
                    standardPooledEp.database(), standardPooledEp.host(), e);
            return false;
        }
    }

    /** Which tier proved pooled health — see {@link #validatePooledDetailed}. */
    public enum ValidationPath {
        /** Full public route (DNS → bridge :15432 → pooler → PG). */
        PUBLIC,
        /** Same pooler + auth_query + tenant SCRAM via loopback; proxy hop unproven. */
        LOOPBACK,
        /** No tier proved health. */
        NONE
    }

    public record PooledValidation(boolean healthy, ValidationPath path) {
    }

    /** Visible for tests so tiers can be stubbed without live PostgreSQL. */
    boolean run(String mode, com.pkmprojects.mongodbserver.model.ConnectionEndpoint ep) {
        // Single-host bridge endpoints share host/port; the JDBC must carry
        // options=-c omnidb.mode= or the proxy fails closed. Legacy ports go
        // straight to PG/PgBouncer and must NOT see the directive.
        String jdbc = engine.isProxyMode() ? strings.toJdbcBridged(ep) : strings.toJdbc(ep);
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

    /**
     * Loopback tier bypasses the proxy — always the plain JDBC, never
     * bridged. Separated from {@link #run} so tests can stub it and so the
     * proxy-mode branch in {@link #run} cannot leak options= to loopback.
     */
    boolean runLoopback(com.pkmprojects.mongodbserver.model.ConnectionEndpoint ep) {
        // Record the endpoint for tests before attempting the connection.
        return runInner("pooled-loopback", strings.toJdbc(ep), ep);
    }

    private boolean runInner(String mode, String jdbc, com.pkmprojects.mongodbserver.model.ConnectionEndpoint ep) {
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
