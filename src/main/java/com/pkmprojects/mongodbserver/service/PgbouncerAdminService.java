package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Best-effort PgBouncer admin-console operations for lifecycle handling
 * (§33/§34): {@code PAUSE}/{@code RESUME} around database deletion so no
 * stale pooled backend survives DROP+recreate, {@code RECONNECT} after
 * password rotation so pooled server connections re-establish under the new
 * credentials. Uses {@code admin_users} (stats users cannot PAUSE).
 *
 * <p>All methods return {@code false} instead of throwing — pooled-state
 * cleanup must never fail a direct-path operation (DIRECT stays available
 * when PgBouncer is down, §57). Callers log/audit as appropriate.
 *
 * <p>Scoping: every command addresses exactly one database via a
 * double-quoted identifier ({@link #quoted}); the admin protocol has no
 * wildcard form for these commands, so one database's lifecycle can never
 * pause/kill a neighbor's pool. {@code KILL} is deliberately not offered —
 * {@code PAUSE} + {@code pg_terminate_backend} reaches the same end-state
 * for deletion without severing in-flight application queries.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PgbouncerAdminService {
    private static final Logger log = LoggerFactory.getLogger(PgbouncerAdminService.class);
    private final PgbouncerProperties properties;

    // Single-port proxy TLS (S-06): when the proxy is configured, Nginx uses TLS
    // passthrough, so client TLS terminates at PgBouncer (client_tls_sslmode=require
    // in compose) and even loopback admin connections must use sslmode=require.
    // Otherwise the pooler is plaintext (two-port TLS-termination model) and admin
    // stays sslmode=disable. Setter-injected optional so tests keep working.
    private volatile com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties;

    @Autowired(required = false)
    public void setProxyProperties(com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    @Autowired
    public PgbouncerAdminService(PgbouncerProperties properties) {
        this.properties = properties;
    }

    /** Hold new pooled clients + drain servers for {@code dbName}. */
    public boolean pauseDb(String dbName) {
        return exec("PAUSE", dbName);
    }

    /** Release clients held by {@link #pauseDb}. Always call in {@code finally}. */
    public boolean resumeDb(String dbName) {
        return exec("RESUME", dbName);
    }

    /** Close pooled server connections so rotation takes effect promptly. */
    public boolean reconnectDb(String dbName) {
        return exec("RECONNECT", dbName);
    }

    private boolean exec(String command, String dbName) {
        String target = quoted(dbName);
        try (PgbouncerConsoleClient c = openAdminConnection()) {
            c.query(command + " " + target);
            log.info("PgBouncer {} {} ok", command, dbName);
            return true;
        } catch (Exception e) {
            // Never include credentials — db name + command only.
            log.warn("PgBouncer {} {} failed (continuing direct-path operation): {}", command, dbName, e.getMessage());
            return false;
        }
    }

    /** sslmode for pooler admin connections: require iff the TLS-passthrough proxy is configured. */
    String poolerSslMode() {
        return proxyProperties != null && proxyProperties.isConfigured() ? "require" : "disable";
    }

    private PgbouncerConsoleClient openAdminConnection() throws Exception {
        String pass = properties.adminPassword();
        // Raw protocol client (see PgbouncerConsoleClient): pgjdbc's
        // connect-time SET probe dies on the console db with "SET failed".
        return PgbouncerConsoleClient.connect("127.0.0.1", properties.port(),
                properties.adminUser(), pass == null ? "" : pass,
                poolerSslMode().equals("require"), 2000, 5000);
    }

    /** Quote an admin-console identifier (db names are pre-validated upstream). */
    static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
