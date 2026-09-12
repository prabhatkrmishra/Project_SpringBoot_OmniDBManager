package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.DatabaseConnections;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over a database engine (MongoDB / PostgreSQL).
 * Each engine owns its DDL, user management, and connection-string format.
 * PgBouncer stays PostgreSQL-specific: the generic contract is
 * {@link #connectionEndpoints(String, String, String, boolean)} which returns
 * DIRECT-only for Mongo/MySQL and DIRECT+POOLED for Postgres when pooled.
 */
public interface DatabaseEngine {

    DatabaseEngineType type();

    void createUser(String dbName, String userName, String password);

    void createDatabase(String dbName, String owner);

    void dropDatabase(String dbName);

    void dropUser(String dbName, String userName);

    void updateUserPassword(String dbName, String userName, String newPassword);

    boolean databaseExists(String dbName);

    List<String> listDatabaseNames();

    Map<String, Long> getDatabaseSizes();

    String buildConnectionString(String userName, String password, String dbName);

    List<String> getUsers(String dbName);

    void ping();

    default void grantPrivileges(String dbName, String userName) {}

    /**
     * Generic endpoint view. Default = DIRECT-only so Mongo/MySQL need no
     * PgBouncer concepts. Postgres overrides to include POOLED when enabled.
     * Password is in-memory only for string building; metadata APIs must use
     * {@code withoutSecret()}.
     */
    default DatabaseConnections connectionEndpoints(String dbName, String userName, String password, boolean pooled) {
        com.pkmprojects.mongodbserver.model.SslMode ssl = com.pkmprojects.mongodbserver.model.SslMode.REQUIRE;
        com.pkmprojects.mongodbserver.model.ConnectionEndpoint direct =
                new com.pkmprojects.mongodbserver.model.ConnectionEndpoint(
                        "127.0.0.1", 0, dbName, userName, password, ssl,
                        com.pkmprojects.mongodbserver.model.ConnectionMode.DIRECT, null);
        return new DatabaseConnections(direct, null);
    }

    // ── PgBouncer pooled-auth (auth_query lookup) ───────────────────────
    // No-op default so non-pooler engines (Mongo/MySQL) need no changes;
    // PostgresDatabaseEngine overrides with role + function install.

    default void installPooledAuth(String dbName) {
        throw new UnsupportedOperationException("Pooled auth is not supported by " + type());
    }

    default boolean isPooledAuthInstalled(String dbName) { return false; }

    // ── Vector extensions (pgvector) ─────────────────────────────────────
    // Defaults are no-ops so non-vector engines (Mongo/MySQL) and future
    // engines don't need to implement them; PostgresDatabaseEngine overrides.

    default boolean isVectorAvailable() { return false; }

    default boolean isVectorEnabled(String dbName) { return false; }

    default void enableVector(String dbName) {
        throw new UnsupportedOperationException("Vector extensions are not supported by " + type());
    }

    default String vectorVersion(String dbName) { return null; }
}
