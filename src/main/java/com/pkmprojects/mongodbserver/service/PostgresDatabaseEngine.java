package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresDatabaseEngine implements DatabaseEngine {

    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseEngine.class);

    private final PostgresDatabaseRepository postgresDatabaseRepository;
    private final Environment environment;
    private final String postgresUri;
    private final String issuedHost;
    private final int issuedPort;
    private final String sslmode;
    private final com.pkmprojects.mongodbserver.config.PgbouncerProperties pgbouncerProperties;

    // Single-port proxy (S-06). Setter-injected optional so all existing
    // constructors/tests keep working; null = legacy two-port behavior.
    // When configured, pooled strings carry only proxy hostname + public port; direct stays internal in pooled-only shape.
    private volatile com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProxyProperties(com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PostgresDatabaseEngine(PostgresDatabaseRepository postgresDatabaseRepository,
                                   Environment environment,
                                   @Value("${app.postgres.uri:jdbc:postgresql://127.0.0.1:9813/postgres}") String postgresUri,
                                   @Value("${app.postgres.issued-host:}") String issuedHost,
                                   @Value("${app.postgres.issued-port:5432}") int issuedPort,
                                   @Value("${app.postgres.sslmode:require}") String sslmode,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) com.pkmprojects.mongodbserver.config.PgbouncerProperties pgbouncerProperties) {
        this.postgresDatabaseRepository = postgresDatabaseRepository;
        this.environment = environment;
        this.postgresUri = postgresUri;
        this.issuedHost = issuedHost;
        this.issuedPort = validatePort(issuedPort, "app.postgres.issued-port");
        this.sslmode = sslmode;
        this.pgbouncerProperties = pgbouncerProperties;
        if (pgbouncerProperties != null) {
            validatePort(pgbouncerProperties.issuedPort(), "app.pgbouncer.issued-port");
            validatePort(pgbouncerProperties.port(), "app.pgbouncer.port");
        }
    }

    // Legacy constructor for tests without PgbouncerProperties (used via direct new in unit tests)
    public PostgresDatabaseEngine(PostgresDatabaseRepository postgresDatabaseRepository,
                                   Environment environment,
                                   String postgresUri,
                                   String publicHost,
                                   boolean publicTls,
                                   String publicSslmode) {
        this(postgresDatabaseRepository, environment, postgresUri, publicHost, 5432, publicSslmode, null);
    }

    // Legacy 6-arg without TLS bool (new shape) - for tests that pass host without port
    public PostgresDatabaseEngine(PostgresDatabaseRepository postgresDatabaseRepository,
                                   Environment environment,
                                   String postgresUri,
                                   String issuedHost,
                                   String sslmode) {
        this(postgresDatabaseRepository, environment, postgresUri, issuedHost, 5432, sslmode, null);
    }

    // Test helper with explicit direct port
    public PostgresDatabaseEngine(PostgresDatabaseRepository postgresDatabaseRepository,
                                   Environment environment,
                                   String postgresUri,
                                   String issuedHost,
                                   int issuedPort,
                                   String sslmode) {
        this(postgresDatabaseRepository, environment, postgresUri, issuedHost, issuedPort, sslmode, null);
    }

    @Override
    public DatabaseEngineType type() {
        return DatabaseEngineType.POSTGRES;
    }

    @Override
    public void createUser(String dbName, String userName, String password) {
        postgresDatabaseRepository.createUser(dbName, userName, password);
    }

    @Override
    public void createDatabase(String dbName, String owner) {
        postgresDatabaseRepository.createDatabase(dbName, owner);
    }

    @Override
    public void dropDatabase(String dbName) {
        postgresDatabaseRepository.dropDatabase(dbName);
    }

    @Override
    public void dropUser(String dbName, String userName) {
        postgresDatabaseRepository.dropUser(dbName, userName);
    }

    @Override
    public void updateUserPassword(String dbName, String userName, String newPassword) {
        postgresDatabaseRepository.updateUserPassword(dbName, userName, newPassword);
    }

    @Override
    public boolean databaseExists(String dbName) {
        return postgresDatabaseRepository.databaseExists(dbName);
    }

    @Override
    public List<String> listDatabaseNames() {
        return postgresDatabaseRepository.listDatabaseNames();
    }

    @Override
    public Map<String, Long> getDatabaseSizes() {
        return postgresDatabaseRepository.getDatabaseSizes();
    }

    @Override
    public List<String> getUsers(String dbName) {
        return postgresDatabaseRepository.getUsers(dbName);
    }

    @Override
    public void ping() {
        postgresDatabaseRepository.ping();
    }

    @Override
    public void grantPrivileges(String dbName, String userName) {
        postgresDatabaseRepository.grantPrivileges(dbName, userName);
    }

    /**
     * Installs the PgBouncer {@code auth_query} path for a pooled database:
     * cluster-wide least-privilege auth role + per-database lookup function.
     * Requires pooler config (throws when pooling is not configured) and a
     * non-blank auth password (never installs with a default/empty secret).
     */
    public void installPooledAuth(String dbName) {
        if (pgbouncerProperties == null) {
            throw new IllegalStateException("Pooling is not configured for database '" + dbName + "' — app.pgbouncer is missing");
        }
        String authPassword = pgbouncerProperties.authPassword();
        if (authPassword == null || authPassword.isBlank()) {
            throw new IllegalStateException("Refusing to install pooled auth for '" + dbName + "' with a blank PGBOUNCER_AUTH_PASSWORD");
        }
        postgresDatabaseRepository.ensureAuthRole(
                com.pkmprojects.mongodbserver.config.PgbouncerProperties.AUTH_USER, authPassword);
        postgresDatabaseRepository.installAuthLookup(
                dbName, com.pkmprojects.mongodbserver.config.PgbouncerProperties.AUTH_USER);
    }

    public boolean isPooledAuthInstalled(String dbName) {
        return postgresDatabaseRepository.isAuthLookupInstalled(dbName);
    }

    /** True only when the single-port proxy is explicitly configured (S-06 gate). */
    boolean isProxyMode() {
        return proxyProperties != null && proxyProperties.isConfigured();
    }

    @Override
    public com.pkmprojects.mongodbserver.model.DatabaseConnections connectionEndpoints(
            String dbName, String userName, String password, boolean pooled) {
        com.pkmprojects.mongodbserver.model.SslMode mode =
                com.pkmprojects.mongodbserver.model.SslMode.parse(sslmode,
                        com.pkmprojects.mongodbserver.model.SslMode.REQUIRE);
        if (isProxyMode()) {
            // Dual-link serves direct via proxy; pooled-only public keeps direct
            // on the internal address (no public direct route exists).
            com.pkmprojects.mongodbserver.model.ConnectionEndpoint direct = proxyProperties.directViaProxy()
                    ? new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                            proxyProperties.directHost().trim() + ":" + proxyProperties.port(),
                            proxyProperties.port(), dbName, userName, password, mode,
                            com.pkmprojects.mongodbserver.model.ConnectionMode.DIRECT, null)
                    : new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                            resolveDirectHost(), issuedPort, dbName, userName, password, mode,
                            com.pkmprojects.mongodbserver.model.ConnectionMode.DIRECT, null);
            if (!pooled) return new com.pkmprojects.mongodbserver.model.DatabaseConnections(direct, null);
            com.pkmprojects.mongodbserver.model.ConnectionEndpoint pooledEp =
                    new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                            proxyProperties.pooledHost().trim() + ":" + proxyProperties.port(),
                            proxyProperties.port(), dbName, userName, password, mode,
                            com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED,
                            com.pkmprojects.mongodbserver.model.PoolMode.TRANSACTION);
            return new com.pkmprojects.mongodbserver.model.DatabaseConnections(direct, pooledEp);
        }
        com.pkmprojects.mongodbserver.model.ConnectionEndpoint direct =
                new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                        resolveDirectHost(), issuedPort, dbName, userName, password, mode,
                        com.pkmprojects.mongodbserver.model.ConnectionMode.DIRECT, null);
        if (!pooled) return new com.pkmprojects.mongodbserver.model.DatabaseConnections(direct, null);
        int pooledPort = pgbouncerProperties != null ? pgbouncerProperties.issuedPort() : 6432;
        com.pkmprojects.mongodbserver.model.ConnectionEndpoint pooledEp =
                new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                        resolvePooledHost(), pooledPort, dbName, userName, password, mode,
                        com.pkmprojects.mongodbserver.model.ConnectionMode.POOLED,
                        com.pkmprojects.mongodbserver.model.PoolMode.TRANSACTION);
        return new com.pkmprojects.mongodbserver.model.DatabaseConnections(direct, pooledEp);
    }

    public boolean isVectorAvailable() {
        return postgresDatabaseRepository.isVectorAvailable();
    }

    public boolean isVectorEnabled(String dbName) {
        return postgresDatabaseRepository.isVectorEnabled(dbName);
    }

    public void enableVector(String dbName) {
        postgresDatabaseRepository.enableVectorExtension(dbName);
    }

    public String vectorVersion(String dbName) {
        return postgresDatabaseRepository.vectorVersion(dbName);
    }

    @Override
    public String buildConnectionString(String userName, String password, String dbName) {
        // Proxy mode: pooled-only public keeps direct on the internal address
        // (no public direct route exists); dual-link serves direct via proxy.
        String host = (isProxyMode() && proxyProperties.directViaProxy())
                ? proxyProperties.directHost().trim() + ":" + proxyProperties.port()
                : resolveDirectHost();
        String encodedUser = uriEncode(userName);
        String encodedPass = uriEncode(password);
        String encodedDb = uriEncode(dbName);
        String base = "postgresql://" + encodedUser + ":" + encodedPass + "@" + host + "/" + encodedDb;
        // sslmode is enum-based (Postgres only) — always included
        return base + "?sslmode=" + sslmode + "&application_name=omnidb";
    }

    /**
     * Pooled connection string for DBs provisioned with pooling enabled.
     * Routes through PgBouncer public port (PGBOUNCER_ISSUED_PORT), not the internal loopback port.
     * Host is DNS-only; port comes from app.pgbouncer.issued-port.
     */
    public String buildPooledConnectionString(String userName, String password, String dbName) {
        String host = isProxyMode()
                ? proxyProperties.pooledHost().trim() + ":" + proxyProperties.port()
                : resolvePooledHost();
        String base = "postgresql://" + uriEncode(userName) + ":" + uriEncode(password) + "@" + host + "/" + uriEncode(dbName);
        return base + "?sslmode=" + sslmode + "&application_name=omnidb";
    }

    /**
     * Direct host for migrations/admin: DNS-only host + POSTGRES_ISSUED_PORT.
     * When host is blank, falls back to 127.0.0.1:9813 (local dev) or derived from postgresUri.
     */
    public String resolveDirectHost() {
        if (issuedHost == null || issuedHost.isBlank()) {
            // Derive from jdbc:postgresql://host:port/db
            String uri = postgresUri;
            int schemeEnd = uri.indexOf("://");
            if (schemeEnd < 0) {
                return "127.0.0.1:9813";
            }
            int slash = uri.indexOf('/', schemeEnd + 3);
            String hostPort = slash >= 0 ? uri.substring(schemeEnd + 3, slash) : uri.substring(schemeEnd + 3);
            int q = hostPort.indexOf('?');
            if (q >= 0) hostPort = hostPort.substring(0, q);
            return hostPort.isBlank() ? "127.0.0.1:9813" : hostPort;
        }
        String hostOnly = stripLegacyPort(issuedHost.trim());
        return hostOnly + ":" + issuedPort;
    }

    /**
     * Pooled host for app/workers: same DNS host + PGBOUNCER_ISSUED_PORT.
     * When host is blank, returns 127.0.0.1:6432 (local pooled) or derived host with pooled port.
     */
    public String resolvePooledHost() {
        int pooledPort = 6432;
        if (pgbouncerProperties != null) {
            pooledPort = pgbouncerProperties.issuedPort();
        }
        if (issuedHost == null || issuedHost.isBlank()) {
            // For local dev, pooled is 127.0.0.1:6432 regardless of direct port
            // Derive host part from postgresUri but swap port to pooled
            String direct = resolveDirectHost();
            // direct is like 127.0.0.1:9813 or derived host:port
            int colon = direct.lastIndexOf(':');
            if (colon < 0) {
                return direct + ":" + pooledPort;
            }
            String hostOnly = direct.substring(0, colon);
            return hostOnly + ":" + pooledPort;
        }
        String hostOnly = stripLegacyPort(issuedHost.trim());
        return hostOnly + ":" + pooledPort;
    }

    /**
     * Legacy alias for tests — now delegates to direct host.
     */
    String resolveHost() {
        return resolveDirectHost();
    }

    private String stripLegacyPort(String host) {
        if (host != null && host.contains(":")) {
            log.warn("POSTGRES_ISSUED_HOST should be DNS-only without port, stripping legacy port from '{}' — use POSTGRES_ISSUED_PORT / PGBOUNCER_ISSUED_PORT instead", host);
            int colon = host.lastIndexOf(':');
            String hostOnly = host.substring(0, colon).trim();
            // Handle case where host was like "pg.example.com:5432" -> "pg.example.com"
            // If hostOnly still blank, return original trimmed host without port part
            return hostOnly.isBlank() ? host.trim() : hostOnly;
        }
        return host;
    }

    private static int validatePort(int port, String property) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(property + " must be between 1 and 65535, got " + port);
        }
        return port;
    }

    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return encoded.toString();
    }
}
