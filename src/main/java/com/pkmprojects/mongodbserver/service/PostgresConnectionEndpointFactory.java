package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.config.DatabaseProxyProperties;
import com.pkmprojects.mongodbserver.model.ConnectionEndpoint;
import com.pkmprojects.mongodbserver.model.ConnectionMode;
import com.pkmprojects.mongodbserver.model.DatabaseConnections;
import com.pkmprojects.mongodbserver.model.InternalDatabaseEndpoint;
import com.pkmprojects.mongodbserver.model.PoolMode;
import com.pkmprojects.mongodbserver.model.SslMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single place that knows public host/port + TLS + mode. Callers pass
 * identity/credentials; the factory supplies the correct public endpoint —
 * no {@code url.replace(":5432",":6432")} anywhere.
 */
@Component
public class PostgresConnectionEndpointFactory {
    private final String issuedHost;
    private final int issuedPort;
    private final SslMode sslMode;
    private final PgbouncerProperties pgbouncerProperties;
    private volatile DatabaseProxyProperties proxyProperties;

    public PostgresConnectionEndpointFactory(
            @Value("${app.postgres.issued-host:}") String issuedHost,
            @Value("${app.postgres.issued-port:5432}") int issuedPort,
            @Value("${app.postgres.sslmode:require}") String sslmode,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PgbouncerProperties pgbouncerProperties) {
        this.issuedHost = issuedHost == null ? "" : issuedHost.trim();
        this.issuedPort = issuedPort;
        this.sslMode = SslMode.parse(sslmode, SslMode.REQUIRE);
        this.pgbouncerProperties = pgbouncerProperties;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProxyProperties(DatabaseProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    boolean isProxyMode() {
        return proxyProperties != null && proxyProperties.isConfigured();
    }

    public ConnectionEndpoint direct(String dbName, String user, String password) {
        if (isProxyMode()) {
            return new ConnectionEndpoint(
                    proxyProperties.directHost().trim() + ":" + proxyProperties.port(),
                    proxyProperties.port(), dbName, user, password,
                    sslMode, ConnectionMode.DIRECT, null);
        }
        return new ConnectionEndpoint(publicHost(issuedPort), issuedPort, dbName, user, password,
                sslMode, ConnectionMode.DIRECT, null);
    }

    public ConnectionEndpoint pooled(String dbName, String user, String password) {
        if (isProxyMode()) {
            return new ConnectionEndpoint(
                    proxyProperties.pooledHost().trim() + ":" + proxyProperties.port(),
                    proxyProperties.port(), dbName, user, password,
                    sslMode, ConnectionMode.POOLED, PoolMode.TRANSACTION);
        }
        int port = pgbouncerProperties != null ? pgbouncerProperties.issuedPort() : 6432;
        return new ConnectionEndpoint(publicHost(port), port, dbName, user, password,
                sslMode, ConnectionMode.POOLED, PoolMode.TRANSACTION);
    }

    public DatabaseConnections both(String dbName, String user, String password, boolean pooledEnabled) {
        if (!pooledEnabled) return new DatabaseConnections(direct(dbName, user, password), null);
        return new DatabaseConnections(direct(dbName, user, password), pooled(dbName, user, password));
    }

    public InternalDatabaseEndpoint internalPostgres() {
        return InternalDatabaseEndpoint.postgres("postgres", 5432);
    }

    public InternalDatabaseEndpoint internalPgbouncer(int port) {
        return InternalDatabaseEndpoint.pgbouncer("pgbouncer", port);
    }

    private String publicHost(int port) {
        String hostOnly = issuedHost.contains(":") ? issuedHost.substring(0, issuedHost.lastIndexOf(':')).trim() : issuedHost;
        if (hostOnly.isBlank()) return "127.0.0.1:" + port;
        return hostOnly + ":" + port;
    }

    /** Split {@code host:port} display value into host part for UI/API. */
    public String publicHostOnly() {
        if (issuedHost.contains(":")) {
            String h = issuedHost.substring(0, issuedHost.lastIndexOf(':')).trim();
            return h.isBlank() ? issuedHost.trim() : h;
        }
        return issuedHost;
    }
}
